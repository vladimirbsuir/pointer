package dev.pointer.provider;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "models")
public class ProviderModel extends BaseEntity {

    static final String DISCOVERED = "discovered";
    static final String MANUAL = "manual";

    private UUID providerId;
    private String modelKey;
    private String displayName;
    private Integer contextWindow;
    private Integer maxOutputTokens;
    private boolean supportsTools;
    private boolean supportsVision;
    private BigDecimal inputPricePerMtok;
    private BigDecimal outputPricePerMtok;
    private String source;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProviderModel() {}

    ProviderModel(UUID providerId, String modelKey, String displayName, String source, Instant now) {
        super(Ids.newId());
        this.providerId = providerId;
        this.modelKey = modelKey;
        this.displayName = displayName;
        this.source = source;
        this.supportsTools = true;
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getModelKey() {
        return modelKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public boolean supportsTools() {
        return supportsTools;
    }

    public boolean supportsVision() {
        return supportsVision;
    }

    public BigDecimal getInputPricePerMtok() {
        return inputPricePerMtok;
    }

    public BigDecimal getOutputPricePerMtok() {
        return outputPricePerMtok;
    }

    public String getSource() {
        return source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    void update(ProviderDtos.UpdateModelRequest request, Instant now) {
        if (request.displayName() != null) {
            displayName = request.displayName().strip();
        }
        if (request.contextWindow() != null) {
            contextWindow = request.contextWindow();
        }
        if (request.maxOutputTokens() != null) {
            maxOutputTokens = request.maxOutputTokens();
        }
        if (request.supportsTools() != null) {
            supportsTools = request.supportsTools();
        }
        if (request.supportsVision() != null) {
            supportsVision = request.supportsVision();
        }
        if (request.enabled() != null) {
            enabled = request.enabled();
        }
        if (Boolean.TRUE.equals(request.clearPrices())) {
            inputPricePerMtok = null;
            outputPricePerMtok = null;
        }
        if (request.inputPricePerMtok() != null) {
            inputPricePerMtok = request.inputPricePerMtok();
        }
        if (request.outputPricePerMtok() != null) {
            outputPricePerMtok = request.outputPricePerMtok();
        }
        updatedAt = now;
    }
}
