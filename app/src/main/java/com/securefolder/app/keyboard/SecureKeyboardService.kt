package com.securefolderplusplus.app.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View

class SecureKeyboardService : InputMethodService() {
    // Full secure keyboard implementation comes in Step 6.
    // This stub satisfies the manifest reference.
    override fun onCreateInputView(): View {
        return layoutInflater.inflate(
            android.R.layout.simple_list_item_1,
            null
        )
    }
}