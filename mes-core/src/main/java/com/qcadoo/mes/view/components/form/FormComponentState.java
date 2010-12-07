package com.qcadoo.mes.view.components.form;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import com.qcadoo.mes.api.Entity;
import com.qcadoo.mes.internal.DefaultEntity;
import com.qcadoo.mes.model.FieldDefinition;
import com.qcadoo.mes.model.types.HasManyType;
import com.qcadoo.mes.model.validators.ErrorMessage;
import com.qcadoo.mes.utils.ExpressionUtil;
import com.qcadoo.mes.view.ComponentState;
import com.qcadoo.mes.view.FieldEntityIdChangeListener;
import com.qcadoo.mes.view.components.FieldComponentState;
import com.qcadoo.mes.view.states.AbstractContainerState;

public final class FormComponentState extends AbstractContainerState {

    public static final String JSON_ENTITY_ID = "entityId";

    public static final String JSON_VALID = "valid";

    public static final String JSON_HEADER = "header";

    public static final String JSON_HEADER_ENTITY_IDENTIFIER = "headerEntityIdentifier";

    private Long entityId;

    private boolean valid = true;

    private final Map<String, Object> context = new HashMap<String, Object>();

    private final FormEventPerformer eventPerformer = new FormEventPerformer();

    private final String expression;

    private Map<String, FieldComponentState> fieldComponents;

    public FormComponentState(final String expression) {
        this.expression = expression;
        registerEvent("clear", eventPerformer, "clear");
        registerEvent("save", eventPerformer, "save");
        registerEvent("saveAndClear", eventPerformer, "saveAndClear");
        registerEvent("initialize", eventPerformer, "initialize");
        registerEvent("reset", eventPerformer, "initialize");
        registerEvent("delete", eventPerformer, "delete");
    }

    @Override
    public void onFieldEntityIdChange(final Long entityId) {
        setFieldValue(entityId);
        eventPerformer.initialize(new String[0]);
    }

    @Override
    protected void initializeContent(final JSONObject json) throws JSONException {
        if (json.has(JSON_ENTITY_ID) && !json.isNull(JSON_ENTITY_ID)) {
            entityId = json.getLong(JSON_ENTITY_ID);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void initializeContext(final JSONObject json) throws JSONException {
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            String field = iterator.next();
            if ("id".equals(field)) {
                entityId = json.getLong(field);
            } else {
                context.put(field, json.get(field));
            }
        }
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(final Long entityId) {
        this.entityId = entityId;
        requestRender();
        requestUpdateState();
        notifyEntityIdChangeListeners(entityId);
    }

    @Override
    public void setFieldValue(final Object value) {
        setEntityId((Long) value);
    }

    public Entity getEntity() {
        return null; // TODO masz entity ktore mapuje formularz, zmiany na nim wplywaja na wartosci inputow
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public Object getFieldValue() {
        return getEntityId();
    }

    @Override
    protected JSONObject renderContent() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(JSON_ENTITY_ID, entityId);
        json.put(JSON_VALID, isValid());
        if (entityId != null) {
            json.put(JSON_HEADER, getTranslationService().translate(getTranslationPath() + ".headerEdit", getLocale()));
            json.put(JSON_HEADER_ENTITY_IDENTIFIER, getHeader());
        } else {
            json.put(JSON_HEADER, getTranslationService().translate(getTranslationPath() + ".headerNew", getLocale()));
        }
        return json;
    }

    private String getHeader() {
        Entity entity = getDataDefinition().get(entityId);
        return ExpressionUtil.getValue(entity, expression, getLocale());
    }

    private String translateMessage(final String key) {
        List<String> codes = Arrays.asList(new String[] { getTranslationPath() + "." + key, "core.message." + key });
        return getTranslationService().translate(codes, getLocale());
    }

    private Map<String, FieldComponentState> getFieldComponents() {
        if (fieldComponents != null) {
            return fieldComponents;
        }

        fieldComponents = new HashMap<String, FieldComponentState>();

        for (Map.Entry<String, FieldEntityIdChangeListener> field : getFieldEntityIdChangeListeners().entrySet()) {
            if (isValidFormField(field.getKey(), field.getValue())) {
                fieldComponents.put(field.getKey(), (FieldComponentState) field.getValue());
            }
        }

        return fieldComponents;
    }

    private boolean isValidFormField(final String fieldName, final FieldEntityIdChangeListener component) {
        if (!(component instanceof FieldComponentState)) {
            return false;
        }

        FieldDefinition field = getDataDefinition().getField(fieldName);

        if (field == null || HasManyType.class.isAssignableFrom(field.getType().getClass())) {
            return false;
        }

        return true;
    }

    protected final class FormEventPerformer {

        public void saveAndClear(final String[] args) {
            save(args);
            if (isValid()) {
                clear(args);
            }
        }

        public void save(final String[] args) {
            Entity entity = new DefaultEntity(getDataDefinition().getPluginIdentifier(), getDataDefinition().getName(), entityId);

            copyFieldsToEntity(entity);

            copyContextToEntity(entity);

            entity = getDataDefinition().save(entity);

            if (!entity.isValid()) {
                valid = false;
                requestRender();
                copyMessages(entity.getGlobalErrors());
            }

            copyEntityToFields(entity);

            if (entity.isValid()) {
                setFieldValue(entity.getId());
                addMessage(translateMessage("saveMessage"), MessageType.SUCCESS);
            } else {
                addMessage(translateMessage("saveFailedMessage"), MessageType.FAILURE);
            }

            setFieldsRequiredAndDisables();
        }

        public void delete(final String[] args) {
            if (entityId != null) {
                getDataDefinition().delete(entityId);
                addMessage(translateMessage("deleteMessage"), MessageType.SUCCESS);
            }

            clear(args);
        }

        public void initialize(final String[] args) {
            Entity entity = getFormEntity();

            copyDefaultValuesToFields();

            if (entity != null) {
                copyEntityToFields(entity);
                setFieldValue(entity.getId());
            } else {
                clear(args);
            }

            setFieldsRequiredAndDisables();
        }

        public void clear(final String[] args) {
            clearFields();
            setFieldValue(null);
            setFieldsRequiredAndDisables();
        }

        private Entity getFormEntity() {
            if (entityId != null) {
                return getDataDefinition().get(entityId);
            } else {
                return null;
            }
        }

        private void copyDefaultValuesToFields() {
            for (Map.Entry<String, FieldComponentState> field : getFieldComponents().entrySet()) {
                FieldDefinition fieldDefinition = getDataDefinition().getField(field.getKey());

                if (fieldDefinition.getDefaultValue() != null) {
                    field.getValue().setFieldValue(convertFieldToString(fieldDefinition.getDefaultValue(), field.getKey()));
                }
            }
        }

        private void copyEntityToFields(final Entity entity) {
            for (Map.Entry<String, FieldComponentState> field : getFieldComponents().entrySet()) {
                ErrorMessage message = entity.getError(field.getKey());
                if (message == null) {
                    field.getValue().setFieldValue(convertFieldToString(entity.getField(field.getKey()), field.getKey()));
                } else {
                    copyMessage(field.getValue(), message);
                }
            }
        }

        private String convertFieldToString(final Object value, final String field) {
            if (value != null) {
                return getDataDefinition().getField(field).getType().toString(value);
            } else {
                return "";
            }
        }

        private void copyMessages(final List<ErrorMessage> messages) {
            for (ErrorMessage message : messages) {
                copyMessage(FormComponentState.this, message);
            }
        }

        private void copyMessage(final ComponentState componentState, final ErrorMessage message) {
            if (message != null) {
                String translation = getTranslationService().translate(message.getMessage(), getLocale());
                componentState.addMessage(translation, MessageType.FAILURE);
            }
        }

        private void clearFields() {
            for (Map.Entry<String, FieldComponentState> field : getFieldComponents().entrySet()) {
                field.getValue().setFieldValue(null);
            }
        }

        private void copyFieldsToEntity(final Entity entity) {
            for (Map.Entry<String, FieldComponentState> field : getFieldComponents().entrySet()) {
                entity.setField(field.getKey(), field.getValue().getFieldValue());
            }
        }

        private void copyContextToEntity(final Entity entity) {
            for (String field : getDataDefinition().getFields().keySet()) {
                if (context.containsKey(field)) {
                    entity.setField(field, context.get(field));
                }
            }
        }

        private void setFieldsRequiredAndDisables() {
            for (Map.Entry<String, FieldComponentState> field : getFieldComponents().entrySet()) {
                FieldDefinition fieldDefinition = getDataDefinition().getField(field.getKey());

                if (fieldDefinition.isRequired() || (entityId == null && fieldDefinition.isRequiredOnCreate())) {
                    field.getValue().setRequired(true);
                }

                if (fieldDefinition.isReadOnly() || (entityId != null && fieldDefinition.isReadOnlyOnUpdate())) {
                    field.getValue().setEnabled(false);
                }
            }
        }

    }
}
