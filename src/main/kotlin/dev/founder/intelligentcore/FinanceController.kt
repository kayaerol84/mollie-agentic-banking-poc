package dev.founder.intelligentcore

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/finance")
class FinanceController(private val financeService: FinanceService) {
    @GetMapping("/balance")
    fun balance() = financeService.balance()

    @GetMapping("/transactions")
    fun transactions() = financeService.transactions()

    @PostMapping("/payment-proposals")
    fun preparePayment(@RequestBody request: PreparePaymentRequest) =
        financeService.preparePayment(request)
}
