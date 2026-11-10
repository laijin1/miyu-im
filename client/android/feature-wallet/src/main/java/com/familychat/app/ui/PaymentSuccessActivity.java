package com.familychat.app.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.familychat.app.feature_wallet.R;

import java.util.Locale;

/**
 * 支付成功结果页（密语同款）：蓝圈 ✓ + 「支付成功」+ 金额 + 收款方/支付方式 + 「完成」。
 * 扫码支付、付款码确认付款成功后进入；点「完成」返回上一页。
 */
public class PaymentSuccessActivity extends AppCompatActivity {

    public static final String EXTRA_AMOUNT = "amount";   // 分
    public static final String EXTRA_PAYEE = "payee";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_success);

        long amount = getIntent().getLongExtra(EXTRA_AMOUNT, 0);
        String payee = getIntent().getStringExtra(EXTRA_PAYEE);
        if (payee == null || payee.isEmpty()) payee = "收款人";

        ((TextView) findViewById(R.id.tvSuccessAmount))
                .setText(String.format(Locale.CHINA, "¥%.2f", amount / 100.0));
        ((TextView) findViewById(R.id.tvSuccessPayee)).setText(payee);

        findViewById(R.id.btnSuccessDone).setOnClickListener(v -> finish());
    }
}
