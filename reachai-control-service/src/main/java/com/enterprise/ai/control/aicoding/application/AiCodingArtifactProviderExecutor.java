package com.enterprise.ai.control.aicoding.application;

import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactApplyResult;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactEnvelope;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDescriptor;
import com.enterprise.ai.control.aicoding.provider.AiCodingArtifactContractValidator;
import com.enterprise.ai.control.aicoding.provider.AiCodingTaskKindProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes domain Provider application behind a savepoint.
 *
 * <p>A rejected artifact must not leave partially applied page/onboarding
 * domain writes, while the Kernel still needs to preserve the rejected
 * artifact and validation message. NESTED propagation gives that exact
 * boundary inside the outer artifact transaction.</p>
 */
@Component
public class AiCodingArtifactProviderExecutor {

    private final TransactionTemplate nestedTransaction;
    private final AiCodingArtifactContractValidator contractValidator;

    public AiCodingArtifactProviderExecutor(
            PlatformTransactionManager transactionManager,
            AiCodingArtifactContractValidator contractValidator) {
        this.nestedTransaction = new TransactionTemplate(transactionManager);
        this.nestedTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        this.contractValidator = contractValidator;
    }

    public ArtifactApplyResult apply(
            AiCodingTaskKindProvider provider,
            TaskDescriptor task,
            ArtifactEnvelope artifact) {
        ArtifactApplyResult result = nestedTransaction.execute(status -> {
            try {
                contractValidator.requireValid(
                        artifact.content(),
                        provider.contract().jsonSchema());
                ArtifactApplyResult applied =
                        provider.applyArtifact(task, artifact);
                if (applied == null) {
                    status.setRollbackOnly();
                    return ArtifactApplyResult.rejected(
                            "task provider returned no artifact result");
                }
                if (!applied.applied()) {
                    status.setRollbackOnly();
                }
                return applied;
            } catch (IllegalArgumentException | IllegalStateException ex) {
                status.setRollbackOnly();
                return ArtifactApplyResult.rejected(ex.getMessage());
            }
        });
        return result == null
                ? ArtifactApplyResult.rejected(
                        "task provider returned no artifact result")
                : result;
    }
}
