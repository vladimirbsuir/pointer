package dev.pointer.provider;

import dev.pointer.common.BaseEntity;
import dev.pointer.common.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "models")
@Getter
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
    private BigDecimal cachedInputPricePerMtok;
    private BigDecimal outputPricePerMtok;
    private String embeddingQueryPrefix;
    private String embeddingDocumentPrefix;
    private String coordinateSystem = ResolvedModel.PIXELS;
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
        var prefixes = EmbeddingPrefixes.defaultsFor(modelKey);
        this.embeddingQueryPrefix = prefixes.query();
        this.embeddingDocumentPrefix = prefixes.document();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean supportsTools() {
        return supportsTools;
    }

    public boolean supportsVision() {
        return supportsVision;
    }

    public EmbeddingPrefixes embeddingPrefixes() {
        return new EmbeddingPrefixes(embeddingQueryPrefix, embeddingDocumentPrefix);
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
        if (request.coordinateSystem() != null) {
            coordinateSystem = request.coordinateSystem();
        }
        if (Boolean.TRUE.equals(request.clearPrices())) {
            inputPricePerMtok = null;
            cachedInputPricePerMtok = null;
            outputPricePerMtok = null;
        }
        if (request.inputPricePerMtok() != null) {
            inputPricePerMtok = request.inputPricePerMtok();
        }
        if (request.cachedInputPricePerMtok() != null) {
            cachedInputPricePerMtok = request.cachedInputPricePerMtok();
        }
        if (request.outputPricePerMtok() != null) {
            outputPricePerMtok = request.outputPricePerMtok();
        }
        if (request.embeddingQueryPrefix() != null) {
            embeddingQueryPrefix = new EmbeddingPrefixes(request.embeddingQueryPrefix(), null).query();
        }
        if (request.embeddingDocumentPrefix() != null) {
            embeddingDocumentPrefix = new EmbeddingPrefixes(null, request.embeddingDocumentPrefix()).document();
        }
        updatedAt = now;
    }
}
