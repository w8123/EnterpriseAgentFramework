package com.enterprise.ai.reach.sdk.capability;

public class ReachCapabilityParameter {

    private String name;
    private String type;
    private boolean required;
    private String description;
    private String example;
    private String sourceHint;
    private String dictType;
    private boolean sensitive;
    /**
     * Declared primitive element type for arrays/collections when reflection can
     * determine it. Null deliberately means that the SDK has no element shape.
     */
    private String itemsType;
    /** True only for declared Map inputs. DTO objects remain closed by their annotated fields. */
    private boolean openObject;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public String getSourceHint() {
        return sourceHint;
    }

    public void setSourceHint(String sourceHint) {
        this.sourceHint = sourceHint;
    }

    public String getDictType() {
        return dictType;
    }

    public void setDictType(String dictType) {
        this.dictType = dictType;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public void setSensitive(boolean sensitive) {
        this.sensitive = sensitive;
    }

    public String getItemsType() {
        return itemsType;
    }

    public void setItemsType(String itemsType) {
        this.itemsType = itemsType;
    }

    public boolean isOpenObject() {
        return openObject;
    }

    public void setOpenObject(boolean openObject) {
        this.openObject = openObject;
    }
}
