package ai.futurespeak.friday.tv.web;

/**
 * Read-aloud for channels and HTML cards, through Friday's own voice. Android's WebView has no
 * speechSynthesis, so pages that read to children were silent (Wonder Owl, 2026-11-02). There is
 * no JavaScript bridge: the sandbox wraps every page with a small speechSynthesis stand-in
 * ({@link #POLYFILL}) that calls https://voice.friday.local, which {@link SandboxView} intercepts
 * and answers here. A page can only ask Friday to say short text, ask whether she is still
 * talking, or stop her. Nothing is recorded, nothing leaves the TV through this.
 */
public final class ChannelVoice {
    private ChannelVoice() {}

    /** Friday's voice as channels may use it (set by the screen that shows channels; null when voice is off). */
    public interface Speaker { void say(String text); boolean busy(); void stop(); }

    public static volatile Speaker speaker;

    /** At most this much text per request (a sentence or two). */
    public static final int MAX_TEXT = 400;

    /** One answer: status and a small JSON body. Pure, unit-tested. */
    public static final class Answer {
        public final int status; public final String json;
        Answer(int status, String json) { this.status = status; this.json = json; }
    }

    public static Answer handle(String path, String text) {
        Speaker s = speaker;
        switch (path == null ? "" : path) {
            case "/say": {
                if (s == null) return new Answer(503, "{\"error\":\"voice off\"}");
                String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
                if (t.isEmpty()) return new Answer(400, "{\"error\":\"empty\"}");
                if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT);
                s.say(t);
                return new Answer(200, "{\"ok\":true}");
            }
            case "/busy":
                return new Answer(200, "{\"busy\":" + (s != null && s.busy()) + "}");
            case "/stop":
                if (s != null) s.stop();
                return new Answer(200, "{\"ok\":true}");
            default:
                return new Answer(404, "{}");
        }
    }

    /**
     * The stand-in, put before the page's own scripts. speak() queues; each utterance is sent to
     * Friday, and its onend fires once she has been quiet for two checks in a row (so a page that
     * highlights sentence by sentence stays in step). cancel() stops her. Speech recognition is
     * not available in channels, so it is removed, and pages fall back to buttons.
     */
    public static final String POLYFILL = "<script>(function(){var V='https://voice.friday.local';"
            + "function U(t){this.text=String(t==null?'':t);this.rate=1;this.pitch=1;this.volume=1;this.lang='en-US';this.onend=null;this.onstart=null;this.onerror=null;}"
            + "window.SpeechSynthesisUtterance=U;var q=[],cur=null,timer=0,quiet=0;"
            + "function fin(u,err){if(cur!==u)return;cur=null;clearTimeout(timer);try{if(err&&u.onerror)u.onerror({});else if(u.onend)u.onend({});}catch(e){}next();}"
            + "function wait(u,first){timer=setTimeout(function(){if(cur!==u)return;fetch(V+'/busy').then(function(r){return r.json();}).then(function(j){if(cur!==u)return;"
            + "if(j.busy){quiet=0;wait(u,false);}else if(++quiet>=2&&!first){fin(u,false);}else wait(u,false);}).catch(function(){fin(u,true);});},first?700:250);}"
            + "function next(){if(cur||!q.length)return;var u=q.shift();cur=u;quiet=0;fetch(V+'/say?t='+encodeURIComponent(u.text.slice(0,400))).then(function(r){"
            + "if(!r.ok)throw 0;try{if(u.onstart)u.onstart({});}catch(e){}wait(u,true);}).catch(function(){fin(u,true);});}"
            + "window.speechSynthesis={speak:function(u){if(!(u instanceof U))u=new U(u&&u.text);q.push(u);next();},"
            + "cancel:function(){q=[];cur=null;clearTimeout(timer);fetch(V+'/stop').catch(function(){});},"
            + "getVoices:function(){return[];},pause:function(){},resume:function(){},get speaking(){return !!cur;},get pending(){return q.length>0;}};"
            + "try{delete window.SpeechRecognition;delete window.webkitSpeechRecognition;}catch(e){}"
            + "window.SpeechRecognition=undefined;window.webkitSpeechRecognition=undefined;})();</script>";
}
