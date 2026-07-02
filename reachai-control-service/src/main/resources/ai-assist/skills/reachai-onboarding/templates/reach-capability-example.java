import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import org.springframework.stereotype.Service;

@Service
public class ContractCapability {

    private final ContractService contractService;

    public ContractCapability(ContractService contractService) {
        this.contractService = contractService;
    }

    @ReachCapability(
            name = "contract.query",
            title = "Query contract",
            description = "Query a contract by contract number for AI agent read-only use."
    )
    public ContractDetail queryContract(
            @ReachParam(name = "contractNo", description = "Contract number", required = true)
            String contractNo) {
        return contractService.findByContractNo(contractNo);
    }
}
