package com.focusfeed.app

/**
 * Read-only snapshot of whether a document has painted UI.
 * Counts and colors only: no text, HTML, cookies, or URLs with query strings.
 */
object PagePaintProbe {
    const val JS: String = """
(function(){
  function n(v){return (typeof v==='number'&&isFinite(v))?Math.round(v):-1;}
  function bg(el){try{return (getComputedStyle(el).backgroundColor||'').slice(0,32);}catch(e){return '';}}
  function shown(el){
    try{
      var s=getComputedStyle(el);
      if(s.display==='none'||s.visibility==='hidden'||Number(s.opacity)===0) return false;
      var r=el.getBoundingClientRect();
      return r.width>1&&r.height>1;
    }catch(e){return false;}
  }
  var b=document.body, h=document.documentElement;
  var inputs=document.querySelectorAll('input,textarea');
  var visIn=0; for(var i=0;i<inputs.length;i++) if(shown(inputs[i])) visIn++;
  var buttons=document.querySelectorAll('button,[role="button"]');
  var visBtn=0; for(var j=0;j<buttons.length;j++) if(shown(buttons[j])) visBtn++;
  var textChars=0;
  try{ textChars=(b&&b.innerText)?b.innerText.replace(/\s+/g,'').length:0; }catch(e){}
  var sheets=-1; try{sheets=document.styleSheets.length;}catch(e2){}
  return JSON.stringify({
    ready:document.readyState,
    child:b?b.children.length:-1,
    textChars:textChars,
    inputs:inputs.length,
    visInputs:visIn,
    visButtons:visBtn,
    bodyH:b?n(b.getBoundingClientRect().height):-1,
    htmlH:h?n(h.getBoundingClientRect().height):-1,
    innerH:n(window.innerHeight),
    innerW:n(window.innerWidth),
    scrollH:b?n(b.scrollHeight):-1,
    bodyBg:b?bg(b):'',
    htmlBg:h?bg(h):'',
    sheets:sheets,
    ffCss:!!document.getElementById('focusfeed-css'),
    ffHidden:document.querySelectorAll('[data-ff-hidden]').length
  });
})()
"""

    /** Compact, privacy-safe line for the on-screen bar and logcat. */
    fun summarize(raw: String?): String {
        val json = unwrap(raw) ?: return "probe=unavailable"
        fun num(key: String): String = Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1) ?: "?"
        fun bool(key: String): String = Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1) ?: "?"
        fun str(key: String): String =
            Regex(""""$key"\s*:\s*"([^"]{0,32})"""").find(json)?.groupValues?.get(1) ?: "?"
        return "chars=${num("textChars")} in=${num("visInputs")}/${num("inputs")} " +
            "btn=${num("visButtons")} body=${num("bodyH")} inner=${num("innerH")}x${num("innerW")} " +
            "sheets=${num("sheets")} ffCss=${bool("ffCss")} bg=${str("bodyBg")}"
    }

    /** True when the document has a visible control or more than a title's worth of text. */
    fun looksPainted(raw: String?): Boolean {
        val json = unwrap(raw) ?: return false
        fun num(key: String) = Regex(""""$key"\s*:\s*(-?\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return num("visInputs") > 0 || num("visButtons") > 0 || num("textChars") > 40
    }

    private fun unwrap(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return s
    }
}
