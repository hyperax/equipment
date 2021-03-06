package ru.softbalance.equipment.model.atol

import android.content.Context
import android.util.Log
import com.atol.drivers.fptr.Fptr
import com.atol.drivers.fptr.IFptr
import io.reactivex.Observable
import ru.softbalance.equipment.R
import ru.softbalance.equipment.model.*
import java.math.BigDecimal
import java.util.*

class Atol(val context: Context,
           val settings: String) : EcrDriver {

    private val driver: IFptr by lazy { prepareDriver() }

    private var lastInfo = ""

    companion object {
        private val RESULT_OK = 0
    }

    override fun execute(tasks: List<Task>): Observable<EquipmentResponse> {
        return Observable.fromCallable { executeTasksInternal(tasks) }
    }

    fun finish() {
        try {
            driver.destroy()
        } catch (e: Exception) {
            Log.e(Atol::class.java.simpleName, null, e)
        }
    }

    private fun Int.isOK() = this == RESULT_OK
    private fun Int.isFail() = this != RESULT_OK

    @Throws(IllegalArgumentException::class)
    private fun prepareDriver(): IFptr {
        val driver = Fptr()
        driver.create(context)

        if (settings.isNotEmpty() && driver.put_DeviceSettings(settings).isFail()) {
            val initFailure = context.getString(R.string.equipment_init_failure)
            val incorrectSettings = context.getString(R.string.equipment_incorrect_settings)
            throw IllegalArgumentException("$initFailure : $incorrectSettings. ${getInfo()}")
        }

        return driver
    }

    private fun getInfo(): String {
        val info: String
        if (lastInfo.isNotEmpty()) {
            info = lastInfo
            lastInfo = ""
        } else {
            info = context.getString(R.string.equipment_error_code,
                    driver._ResultCode,
                    driver._ResultDescription)
        }

        return info
    }

    private fun executeTasksInternal(tasks: List<Task>): EquipmentResponse {
        if (driver.put_DeviceEnabled(true).isFail()) {
            return EquipmentResponse().apply {
                resultCode = ResponseCode.HANDLING_ERROR
                resultInfo = getInfo()
            }
        }

        tasks.forEach {
            if (!executeTask(it)) {
                return EquipmentResponse().apply {
                    resultCode = ResponseCode.HANDLING_ERROR
                    resultInfo = getInfo()
                }
            }
        }

        driver.put_DeviceEnabled(false)

        return EquipmentResponse().apply {
            resultCode = ResponseCode.SUCCESS
            resultInfo = getInfo()
        }
    }

    private fun executeTask(task: Task): Boolean {
        return when (task.type) {
            TaskType.STRING -> printString(task)
            TaskType.REGISTRATION -> registration(task)
            TaskType.CLOSE_CHECK -> closeCheck()
            TaskType.CANCEL_CHECK -> cancelCheck()
            TaskType.OPEN_CHECK_SELL -> openCheckSell()
            TaskType.PAYMENT -> payment(task)
            TaskType.OPEN_CHECK_RETURN -> openCheckReturn()
            TaskType.RETURN -> refund(task)
            TaskType.CASH_INCOME -> cashOperation(IFptr.CASH_INCOME, task)
            TaskType.CASH_OUTCOME -> cashOperation(IFptr.CASH_INCOME, task)
            TaskType.CLIENT_CONTACT -> setClientContact(task)
            TaskType.REPORT -> report(task)
            TaskType.SYNC_TIME -> syncTime(task)
            TaskType.PRINT_HEADER -> printHeader(task)
            TaskType.PRINT_FOOTER -> printFooter(task)
            TaskType.CUT -> cut(task)
            else -> {
                Log.e(Atol::class.java.simpleName, "The operation type ${task.type} isn't supported")
                return false
            }
        }
    }

    private fun registration(task: Task): Boolean {
        return prepareItemRegistration(task) && driver.Registration().isOK()
    }

    private fun refund(task: Task): Boolean {
        return prepareItemRegistration(task) && driver.Return().isOK()
    }

    private fun prepareItemRegistration(task: Task): Boolean {
        if (task.data.isEmpty()) {
            lastInfo = context.getString(R.string.equipment_incorrect_product_name)
            return false
        } else {
            driver.put_Name(task.data)
        }

        val price = task.params.price
        if (price == null || price <= BigDecimal.ZERO) {
            lastInfo = context.getString(R.string.equipment_incorrect_product_price)
            return false
        } else {
            driver.put_Price(price.toDouble())
        }

        val quantity = task.params.quantity
        if (quantity == null || quantity <= BigDecimal.ZERO) {
            lastInfo = context.getString(R.string.equipment_incorrect_product_quantity)
            return false
        } else {
            driver.put_Quantity(quantity.toDouble())
        }

        task.params.tax?.let {
            driver.put_TaxNumber(it)
        }

        return true
    }

    private fun payment(task: Task): Boolean {
        val sum = task.params.sum ?: BigDecimal.ZERO
        if (sum > BigDecimal.ZERO) {
            driver.put_Summ(sum.toDouble())
        } else {
            lastInfo = context.getString(R.string.equipment_payment_incorrect)
            return false
        }

        val typeClose = task.params.typeClose
        if (typeClose == null) {
            lastInfo = context.getString(R.string.equipment_type_close_incorrect)
            return false
        } else {
            driver.put_TypeClose(typeClose)
        }

        return driver.Payment().isOK()
    }

    private fun closeCheck(): Boolean {
        return driver._CheckState == IFptr.CHEQUE_STATE_CLOSED || driver.CloseCheck().isOK()
    }

    private fun cancelCheck(): Boolean {
        return driver.CancelCheck().isOK()
    }

    private fun setClientContact(task: Task): Boolean {
        driver.put_FiscalPropertyNumber(1008)
        driver.put_FiscalPropertyType(IFptr.FISCAL_PROPERTY_TYPE_STRING)
        driver.put_FiscalPropertyValue(task.data)
        return driver.WriteFiscalProperty().isOK()
    }

    private fun cashOperation(cashType: Int, task: Task): Boolean {
        var isOK = false

        if (prepareRegistration()) {
            val amount = task.params.sum ?: BigDecimal.ZERO
            if (amount > BigDecimal.ZERO) {
                driver.put_Summ(amount.toDouble())
                when (cashType) {
                    IFptr.CASH_INCOME -> isOK = driver.CashIncome().isOK()
                    IFptr.CASH_OUTCOME -> isOK = driver.CashOutcome().isOK()
                }
            }
        }

        return isOK
    }

    private fun openCheckSell(): Boolean {
        return prepareRegistration() && openCheck(IFptr.CHEQUE_TYPE_SELL)
    }

    private fun openCheckReturn(): Boolean {
        return prepareRegistration() && openCheck(IFptr.CHEQUE_TYPE_RETURN)
    }

    private fun openCheck(chequeState: Int): Boolean {
        driver.put_CheckType(chequeState)
        return driver.OpenCheck() === RESULT_OK
    }

    private fun printString(task: Task): Boolean {
        driver.put_Caption(task.data)

        val params = task.params
        driver.put_TextWrap(if (params.wrap ?: false) IFptr.WRAP_WORD else IFptr.WRAP_NONE)
        driver.put_Alignment(convertAlign(params.alignment ?: Alignment.LEFT))
        driver.put_FontBold(params.bold ?: false)
        driver.put_FontItalic(params.italic ?: false)

        return driver.PrintString().isOK()
    }

    private fun convertAlign(@Alignment alignment: String): Int {
        when (alignment) {
            Alignment.CENTER -> return IFptr.ALIGNMENT_CENTER
            Alignment.RIGHT -> return IFptr.ALIGNMENT_RIGHT
            else -> return IFptr.ALIGNMENT_LEFT
        }
    }

    private fun prepareRegistration(): Boolean {
        cancelCheck()
        return setMode(IFptr.MODE_REGISTRATION) && openShift()
    }

    private fun openShift(): Boolean {
        // it is hacky cause driver has bug when checking session status, so just force open the shift
        driver.OpenSession()
        return true
    }

    private fun setMode(mode: Int): Boolean {
        if (mode == driver._Mode) {
            return true
        }

        driver.put_UserPassword(driver._UserPassword)
        driver.put_Mode(mode)
        return driver.SetMode().isOK()
    }

    private fun report(task: Task): Boolean {
        cancelCheck()

        val mode: Int
        if (task.params.reportType == ReportType.REPORT_Z) {
            mode = IFptr.MODE_REPORT_CLEAR
        } else {
            mode = IFptr.MODE_REPORT_NO_CLEAR
        }

        if (!setMode(mode)) {
            return false
        }

        val reportType: Int

        when (task.params.reportType) {
            ReportType.REPORT_Z -> reportType = IFptr.REPORT_Z
            ReportType.REPORT_X -> reportType = IFptr.REPORT_X
            ReportType.REPORT_DEPARTMENT -> reportType = IFptr.REPORT_DEPARTMENTS
            ReportType.REPORT_CASHIERS -> reportType = IFptr.REPORT_CASHIERS
            ReportType.REPORT_HOURS -> reportType = IFptr.REPORT_HOURS
            else -> {
                Log.e(Atol::class.java.simpleName, "The report operation ${task.params.reportType} isn't supported")
                return false
            }
        }

        driver.put_ReportType(reportType)
        return driver.Report().isOK()
    }

    private fun cut(task: Task): Boolean {
        driver.PartialCut().isOK() || driver.FullCut().isOK()
        return true
    }

    private fun syncTime(task: Task): Boolean {
        cancelCheck()

        return with(Date()) {
            driver.put_Date(this).isOK()
                    && driver.put_Time(this).isOK()
                    && driver.SetDate().isOK()
                    && driver.SetTime().isOK()
        }
    }

    private fun printHeader(task: Task): Boolean {
        return driver.PrintHeader().isOK()
    }

    private fun printFooter(task: Task): Boolean {
        return driver.PrintFooter().isOK()
    }

    fun getDefaultSettings() : String {
        return driver._DeviceSettings
    }
}

