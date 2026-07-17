package com.falcofemoralis.hdrezkaapp.models

import com.falcofemoralis.hdrezkaapp.utils.WebHttp
import com.falcofemoralis.hdrezkaapp.utils.WebRequest

object BaseModel {
    fun getJsoup(link: String?): WebRequest {
        // Requests run inside the hidden WebView (same-origin fetch), so cookies
        // (incl. cf_clearance) and the User-Agent come from the WebView session.
        return WebHttp.request((link ?: "").replace(" ", "").replace("\n", ""))
    }
}
