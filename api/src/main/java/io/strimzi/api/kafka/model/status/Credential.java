/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.api.kafka.model.UnknownPropertyPreserving;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * Represents a single listener
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "credentialsSecret", "credentials", "password" })
@EqualsAndHashCode
public class Credential implements UnknownPropertyPreserving, Serializable {
    private static final long serialVersionUID = 1L;

    private String credentialsSecret;

    private Map<String, Object> additionalProperties;

    @Description("The name of `Secret` where the credentials are stored")
    public String getCredentialsSecret() {
        return credentialsSecret;
    }

    public void setCredentialsSecret(String credentialsSecret) {
        this.credentialsSecret = credentialsSecret;
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties != null ? this.additionalProperties : emptyMap();
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        if (this.additionalProperties == null) {
            this.additionalProperties = new HashMap<>();
        }
        this.additionalProperties.put(name, value);
    }
}
