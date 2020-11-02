/*
 * Copyright 2020 BDK Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bdwallet.app.ui.wallet.withdraw

import android.app.Dialog
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bdwallet.app.BDWApplication
import org.bdwallet.app.R
import org.bdwallet.app.ui.wallet.balance.BalanceViewModel
import org.bdwallet.app.ui.wallet.util.bitstamp.Bitstamp
import org.bitcoindevkit.bdkjni.Types.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class WithdrawFragment : Fragment() {
    private val withdrawViewModel: WithdrawViewModel by activityViewModels()

    private lateinit var root: View
    private lateinit var reviewDialog: Dialog

    private lateinit var recipientAddress: String
    private lateinit var withdrawAmount: String
    private lateinit var createTxResp: CreateTxResponse

    init {
        lifecycleScope.launch {
            whenStarted {
                while (isActive) { // cancellable computation loop
                    withdrawViewModel.refreshFiatPrice()
                    delay(60000)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the fragment and set up the review dialog
        root = inflater.inflate(R.layout.fragment_withdraw, container, false)
        reviewDialog = Dialog(requireContext())
        reviewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        reviewDialog.setCancelable(false)
        reviewDialog.setContentView(R.layout.dialog_review)

        // Set onClickListener for the review, back, and send buttons
        root.findViewById<Button>(R.id.review_btn).setOnClickListener {
            val recipientEditText = root.findViewById<EditText>(R.id.input_recipient_address)
            val amountEditText = root.findViewById<EditText>(R.id.input_amount)
            recipientEditText.setText(recipientEditText.text.toString().trim())
            amountEditText.setText(amountEditText.text.toString().trim())
            recipientAddress = recipientEditText.text.toString()
            if (amountEditText.text.toString().isNotEmpty()) {
                withdrawAmount = btcToSatoshiString(amountEditText.text.toString())
                if (recipientAddress.isNotEmpty() && withdrawAmount.isNotEmpty() && withdrawAmount != "0") {
                    reviewBtnOnClickListener()
                }
            }
        }
        reviewDialog.findViewById<TextView>(R.id.back_btn_text).setOnClickListener {
            reviewDialog.dismiss()
        }
        reviewDialog.findViewById<TextView>(R.id.send_btn_text).setOnClickListener {
            sendBtnOnClickListener()
        }

        val walletActivity = activity as AppCompatActivity
        walletActivity.supportActionBar!!.show()
        walletActivity.window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.darkBlue)
        return root
    }

    // Check if the transaction inputs are valid:
        // if it's passes, set display values in the review dialog and show the review dialog
        // otherwise, show an error toast and return
    private fun reviewBtnOnClickListener() {
        val feeRate = 1F // TODO change to be a dynamic value before moving to mainnnet
        val addresses: List<Pair<String, String>> = listOf(Pair(recipientAddress, withdrawAmount))
        val app = requireActivity().application as BDWApplication
        // Attempt to create the transaction
        try {
            createTxResp = app.createTx(feeRate, addresses, false, null, null, null)
        } catch (e: Throwable) {
            Log.d("CREATE-TRANSACTION EXCEPTION", "MSG: ".plus(e.message))
            if (e.message == "WalletError(InsufficientFunds)") {
                showInsufficientBalanceToast()
            } else if (e.message!!.substring(0, 8) == "Parsing(") {
                showInvalidAddressToast()
            }
            return
        }

        // The transaction has been validated - set the dialog display values before showing the reviewDialog
        reviewDialog.findViewById<TextView>(R.id.recipient_text).text = recipientAddress
        reviewDialog.findViewById<TextView>(R.id.amount_text).text = withdrawAmount
        reviewDialog.findViewById<TextView>(R.id.fee_text).text = formatFeeText()
        reviewDialog.findViewById<TextView>(R.id.total_text).text = getTotalWithdraw()
        reviewDialog.show()
    }

    // Sign and broadcast a transaction after it's been verified, created, and reviewed by the user (using BDK)
    private fun sendBtnOnClickListener() {
        val app = requireActivity().application as BDWApplication
        try {
            val signResp: SignResponse = app.sign(createTxResp.psbt)
            val rawTx: RawTransaction = app.extract_psbt(signResp.psbt)
            val txid: Txid = app.broadcast(rawTx.transaction)
            // TODO save or display txid?
        } catch (e: Throwable) {
            Log.d("SEND-TRANSACTION EXCEPTION", "MSG: ".plus(e.message))
            e.printStackTrace()
        }
        showTransactionSuccessToast()
    }

    // Convert a BTC-formatted string (X.XXXXXXXX) to satoshi string
    private fun btcToSatoshiString(btcAmount: String): String {
        return "%.8f".format(btcAmount.toDouble() * 100000000).trimEnd('0').trimEnd('.')
    }

    // Return the total withdraw amount String in satoshis (entered withdraw amount plus total fees)
    private fun getTotalWithdraw(): String {
        val totalWithdraw: Long = withdrawAmount.toLong() + createTxResp.details.fees
        return totalWithdraw.toString()
    }

    // return BTC-formatted string with USD conversion for display in reviewDialog
    private suspend fun formatAmountText(satoshiAmount: String): String {
        val formatter = NumberFormat.getCurrencyInstance()
        formatter.currency = Currency.getInstance("USD")
        formatter.maximumFractionDigits = 2
        val quote = Bitstamp().getTickerService().getQuote()
        val rounding = RoundingMode.HALF_EVEN
        val fiatScale = 2
        val formattedValue = formatter.format(BigDecimal(quote.last, MathContext.DECIMAL64).setScale(fiatScale, rounding) * satoshiAmount.toBigDecimal())
        return "$satoshiAmount BTC ($formattedValue USD)"
    }

    // return the total fee BTC formatted string with percentage of withdrawal amount for display in reviewDialog
    private fun formatFeeText(): String {
        // TODO convert this.createTxResp.details.fees to the format: X.XXXXXXXX BTC (X.XXX%)
        // TODO should it also display the USD value (maybe instead of the percentage)?
        return this.createTxResp.details.fees.toString()
    }

    // When the recipient address is invalid, show this toast to signal a problem to the user
    private fun showInvalidAddressToast() {
        val myToast: Toast = Toast.makeText(context, R.string.toast_invalid_address, Toast.LENGTH_SHORT)
        myToast.show()
    }

    // When the wallet does not have sufficient balance, show this toast to signal a problem to the user
    private fun showInsufficientBalanceToast() {
        val myToast: Toast = Toast.makeText(context,R.string.toast_insufficient_balance, Toast.LENGTH_SHORT)
        myToast.show()
    }

    // When the transaction was sent successfully, show this toast to confirm to user
    private fun showTransactionSuccessToast() {
        val myToast: Toast = Toast.makeText(context, "Transaction successful", Toast.LENGTH_SHORT)
        myToast.show()
    }
}
