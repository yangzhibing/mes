/**
 * ***************************************************************************
 * Copyright (c) 2010 Qcadoo Limited
 * Project: Qcadoo MES
 * Version: 1.4
 *
 * This file is part of Qcadoo.
 *
 * Qcadoo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * ***************************************************************************
 */
package com.qcadoo.mes.materialFlowResources.listeners;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.LockAcquisitionException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.qcadoo.mes.basic.ParameterService;
import com.qcadoo.mes.basic.constants.ProductFields;
import com.qcadoo.mes.materialFlowResources.constants.DocumentFields;
import com.qcadoo.mes.materialFlowResources.constants.DocumentState;
import com.qcadoo.mes.materialFlowResources.constants.MaterialFlowResourcesConstants;
import com.qcadoo.mes.materialFlowResources.constants.ParameterFieldsMFR;
import com.qcadoo.mes.materialFlowResources.constants.ResourceFields;
import com.qcadoo.mes.materialFlowResources.exceptions.InvalidResourceException;
import com.qcadoo.mes.materialFlowResources.print.DispositionOrderPdfService;
import com.qcadoo.mes.materialFlowResources.service.ReceiptDocumentForReleaseHelper;
import com.qcadoo.mes.materialFlowResources.service.ResourceManagementService;
import com.qcadoo.mes.materialFlowResources.service.ResourceStockService;
import com.qcadoo.model.api.DataDefinition;
import com.qcadoo.model.api.DataDefinitionService;
import com.qcadoo.model.api.Entity;
import com.qcadoo.model.api.file.FileService;
import com.qcadoo.model.api.search.SearchCriteriaBuilder;
import com.qcadoo.model.api.search.SearchRestrictions;
import com.qcadoo.report.api.ReportService;
import com.qcadoo.view.api.ComponentState;
import com.qcadoo.view.api.ComponentState.MessageType;
import com.qcadoo.view.api.ViewDefinitionState;
import com.qcadoo.view.api.components.FieldComponent;
import com.qcadoo.view.api.components.FormComponent;

@Service
public class DocumentDetailsListeners {

    private static final String L_FORM = "form";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentDetailsListeners.class);

    @Autowired
    private DataDefinitionService dataDefinitionService;

    @Autowired
    private ParameterService parameterService;

    @Autowired
    private ResourceManagementService resourceManagementService;

    @Autowired
    private ResourceStockService resourceStockService;

    @Autowired
    private ReceiptDocumentForReleaseHelper receiptDocumentForReleaseHelper;

    @Autowired
    private ReportService reportService;

    @Autowired
    private FileService fileService;

    @Autowired
    private DispositionOrderPdfService dispositionOrderPdfService;

    @Autowired
    private DocumentErrorsLogger documentErrorsLogger;

    public void printDocument(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        FormComponent documentForm = (FormComponent) view.getComponentByReference(L_FORM);

        Entity document = documentForm.getEntity();

        view.redirectTo("/materialFlowResources/document." + args[0] + "?id=" + document.getId(), true, false);
    }

    public void printDispositionOrder(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        Entity documentPositionParameters = parameterService.getParameter().getBelongsToField(
                ParameterFieldsMFR.DOCUMENT_POSITION_PARAMETERS);

        boolean acceptanceOfDocumentBeforePrinting = documentPositionParameters
                .getBooleanField("acceptanceOfDocumentBeforePrinting");

        if (acceptanceOfDocumentBeforePrinting) {
            createResourcesForDocuments(view, componentState, args);
        }

        FormComponent documentForm = (FormComponent) view.getComponentByReference(L_FORM);

        if (documentForm.isValid()) {
            Entity documentDb = documentForm.getEntity().getDataDefinition().get(documentForm.getEntityId());
            if (StringUtils.isBlank(documentDb.getStringField(DocumentFields.FILE_NAME))) {
                documentDb.setField(DocumentFields.GENERATION_DATE, new Date());
                documentDb = documentDb.getDataDefinition().save(documentDb);
                try {
                    dispositionOrderPdfService.generateDocument(fileService.updateReportFileName(documentDb,
                            DocumentFields.GENERATION_DATE, "materialFlowResources.dispositionOrder.fileName"), componentState
                            .getLocale());
                } catch (Exception e) {
                    LOGGER.error("Error when generate disposition order", e);
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            reportService.printGeneratedReport(view, componentState, new String[] { args[0],
                    MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER, MaterialFlowResourcesConstants.MODEL_DOCUMENT });
        }
    }

    public void onSave(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        FormComponent documentForm = (FormComponent) view.getComponentByReference(L_FORM);

        Entity document = documentForm.getEntity();

        DataDefinition documentDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_DOCUMENT);

        String documentName = document.getStringField(DocumentFields.NAME);

        if (StringUtils.isNotEmpty(documentName)) {
            SearchCriteriaBuilder searchCriteriaBuilder = documentDD.find().add(
                    SearchRestrictions.eq(DocumentFields.NAME, documentName));

            if (document.getId() != null) {
                searchCriteriaBuilder.add(SearchRestrictions.ne("id", document.getId()));
            }

            boolean duplicateName = searchCriteriaBuilder.list().getTotalNumberOfEntities() > 0;

            if (duplicateName) {
                view.addMessage("materialFlow.info.document.name.duplicate", MessageType.INFO, documentName);
            }
        }
    }

    @Transactional
    public void createResourcesForDocuments(final ViewDefinitionState view, final ComponentState componentState,
            final String[] args) {
        DataDefinition documentDD = dataDefinitionService.get(MaterialFlowResourcesConstants.PLUGIN_IDENTIFIER,
                MaterialFlowResourcesConstants.MODEL_DOCUMENT);

        FormComponent documentForm = (FormComponent) view.getComponentByReference(L_FORM);

        Entity document = documentForm.getPersistedEntityWithIncludedFormValues();
        LOGGER.info("DOCUMENT ACCEPT STARTED: id =" + document.getId() + " number = "
                + document.getStringField(DocumentFields.NUMBER));

        if (!DocumentState.DRAFT.getStringValue().equals(document.getStringField(DocumentFields.STATE))) {
            return;
        }

        document.setField(DocumentFields.STATE, DocumentState.ACCEPTED.getStringValue());

        document = documentDD.save(document);

        if (!document.isValid()) {
            document.setField(DocumentFields.STATE, DocumentState.DRAFT.getStringValue());

            documentForm.setEntity(document);
            LOGGER.info("DOCUMENT ACCEPT FAILED: id =" + document.getId() + " number = "
                    + document.getStringField(DocumentFields.NUMBER));
            return;
        }

        if (!document.getHasManyField(DocumentFields.POSITIONS).isEmpty()) {
            try {
                resourceManagementService.createResources(document);
            } catch (InvalidResourceException ire) {
                document.setNotValid();
                String resourceNumber = ire.getEntity().getStringField(ResourceFields.NUMBER);
                String productNumber = ire.getEntity().getBelongsToField(ResourceFields.PRODUCT)
                        .getStringField(ProductFields.NUMBER);
                documentForm.addMessage("materialFlow.document.validate.global.error.invalidResource", MessageType.FAILURE,
                        false, resourceNumber, productNumber);
            }
        } else {
            document.setNotValid();

            documentForm.addMessage("materialFlow.document.validate.global.error.emptyPositions", MessageType.FAILURE);
        }

        if (!document.isValid()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            documentErrorsLogger.saveResourceStockLackErrorsToSystemLogs(document);

            document.setField(DocumentFields.STATE, DocumentState.DRAFT.getStringValue());

            LOGGER.info("DOCUMENT ACCEPT FAILED: id =" + document.getId() + " number = "
                    + document.getStringField(DocumentFields.NUMBER));
        } else {
            documentForm.addMessage("materialFlowResources.success.documentAccepted", MessageType.SUCCESS);

            if (receiptDocumentForReleaseHelper.buildConnectedPZDocument(document)) {
                receiptDocumentForReleaseHelper.tryBuildPz(document, view);
            }

            LOGGER.info("DOCUMENT ACCEPT SUCCESS: id =" + document.getId() + " number = "
                    + document.getStringField(DocumentFields.NUMBER));
        }

        documentForm.setEntity(document);
    }

    public void clearWarehouseFields(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        FieldComponent locationFromField = (FieldComponent) view.getComponentByReference(DocumentFields.LOCATION_FROM);
        locationFromField.setFieldValue(null);
        locationFromField.requestComponentUpdateState();

        FieldComponent locationToField = (FieldComponent) view.getComponentByReference(DocumentFields.LOCATION_TO);
        locationToField.setFieldValue(null);
        locationToField.requestComponentUpdateState();
    }

    public void refreshView(final ViewDefinitionState view, final ComponentState state, final String[] args) {
        FormComponent documentForm = (FormComponent) view.getComponentByReference(L_FORM);

        documentForm.performEvent(view, "refresh");
    }

    public void setCriteriaModifiersParameters(final ViewDefinitionState view, final ComponentState state, final String[] args) {

    }

    public void fillResources(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        FormComponent form = (FormComponent) view.getComponentByReference(L_FORM);
        Entity document = form.getPersistedEntityWithIncludedFormValues();
        try {
            resourceManagementService.fillResourcesInDocument(view, document);
            document = form.getPersistedEntityWithIncludedFormValues();
            form.setEntity(document);
            view.performEvent(view, "reset");
        } catch (IllegalStateException e) {
            LOGGER.warn("Fill resources: " + e.getMessage());
            LOGGER.warn(document.toString());
            view.addMessage("materialFlow.document.fillResources.global.error.documentNotValid", MessageType.FAILURE, false);
        } catch (LockAcquisitionException e) {
            LOGGER.warn("Fill resources: " + e.getMessage());
            LOGGER.warn(document.toString());
            view.addMessage("materialFlow.document.fillResources.global.error.concurrentModify", MessageType.FAILURE, false);
        }
    }

    public void checkResourcesStock(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        FormComponent formComponent = (FormComponent) view.getComponentByReference(L_FORM);
        Entity document = formComponent.getPersistedEntityWithIncludedFormValues();

        resourceStockService.checkResourcesStock(document);
        if (document.getGlobalErrors().isEmpty()) {
            view.addMessage("materialFlow.document.checkResourcesStock.global.message.success", MessageType.SUCCESS, true);
        }
        formComponent.setEntity(document);
    }

    public void addMultipleResources(final ViewDefinitionState view, final ComponentState componentState, final String[] args) {
        FormComponent formComponent = (FormComponent) view.getComponentByReference(L_FORM);
        Entity document = formComponent.getPersistedEntityWithIncludedFormValues();
        Entity warehouseFrom = document.getBelongsToField(DocumentFields.LOCATION_FROM);
        final Map<String, Object> parameters = new HashMap<>();
        parameters.put("documentId", document.getId());
        if (warehouseFrom != null) {
            parameters.put("warehouseId", warehouseFrom.getId());
        }
        JSONObject context = new JSONObject(parameters);
        StringBuilder url = new StringBuilder("../page/materialFlowResources/positionAddMulti.html");
        url.append("?context=");
        url.append(context.toString());

        view.openModal(url.toString());
    }

}
