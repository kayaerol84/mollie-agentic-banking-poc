package dev.founder.intelligentcore.api

import dev.founder.intelligentcore.domain.FinanceService
import dev.founder.intelligentcore.domain.PreparePaymentCommand
import dev.founder.intelligentcore.domain.ProposalStatus
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

data class PreparePaymentRequest(
    val beneficiary: String,
    val amount: BigDecimal,
    val currency: String? = null,
    val idempotencyKey: String,
    val beneficiaryIban: String? = null,
    val reference: String? = null
)

data class DecisionRequest(val approver: String)

@RestController
@RequestMapping("/api/finance")
class FinanceController(private val finance: FinanceService) {

    @GetMapping("/balance")
    fun balance() = finance.balance()

    @GetMapping("/transactions")
    fun transactions(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) limit: Int?
    ) = finance.transactions(from, to, limit)

    @PostMapping("/payment-proposals")
    fun preparePayment(@RequestBody request: PreparePaymentRequest) = finance.preparePayment(
        PreparePaymentCommand(
            beneficiary = request.beneficiary,
            amount = request.amount,
            currency = request.currency,
            idempotencyKey = request.idempotencyKey,
            beneficiaryIban = request.beneficiaryIban,
            reference = request.reference,
            requestedBy = "api"
        )
    )

    @GetMapping("/payment-proposals")
    fun proposals(@RequestParam(required = false) status: ProposalStatus?) = finance.proposals(status)

    @GetMapping("/payment-proposals/{id}")
    fun proposal(@PathVariable id: String) = finance.proposal(id)

    // Human decisions. In a real system these sit behind authentication and step-up auth,
    // and are never reachable by the agent or MCP clients.
    @PostMapping("/payment-proposals/{id}/approve")
    fun approve(@PathVariable id: String, @RequestBody decision: DecisionRequest) = finance.approve(id, decision.approver)

    @PostMapping("/payment-proposals/{id}/decline")
    fun decline(@PathVariable id: String, @RequestBody decision: DecisionRequest) = finance.decline(id, decision.approver)
}
