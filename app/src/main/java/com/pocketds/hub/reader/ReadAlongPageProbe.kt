package com.pocketds.hub.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Asks the page in the web view what it shows of the narration (#49). The script only reports offsets: which
 * narrated elements have any part on the page, and for the first and last of them where the page's first and
 * last character are in the element's text. The maths of it is [ReadAlongPageSync]'s.
 *
 * A character is on the page when its box meets the window, so the same test serves one column, two columns
 * (a spread is one window holding both) and a scrolled page, in either reading direction: the first character
 * of the page is the first one in the book's order that is on it, the last one the last. Only a sentence
 * the page edge cuts through is searched letter by letter; one that begins or ends on the page is decided by its
 * first or last line alone.
 */
object ReadAlongPageProbe {
    /** The script for the narrated elements [ids] of the part of the book being shown. */
    fun script(ids: List<String>): String {
        val list = JsonArray(ids.map(::JsonPrimitive)).toString()
        return "($BODY)($list)"
    }

    private const val BODY = """function(ids){
var W=window.innerWidth,H=window.innerHeight;
function on(r){return (r.width>0||r.height>0)&&r.right>0&&r.left<W&&r.bottom>0&&r.top<H;}
function anyOn(rs){for(var k=0;k<rs.length;k++){if(on(rs[k]))return true;}return false;}
var els=[],visible=[];
for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e&&anyOn(e.getClientRects())){els.push(e);visible.push(ids[i]);}}
if(!els.length)return {first:null,last:null,visible:[]};
var a=els[0],z=els[0];
for(var j=1;j<els.length;j++){if(a.compareDocumentPosition(els[j])&2)a=els[j];if(z.compareDocumentPosition(els[j])&4)z=els[j];}
function map(el){var w=document.createTreeWalker(el,NodeFilter.SHOW_TEXT),nodes=[],text='',n;
while((n=w.nextNode())){nodes.push([n,text.length]);text+=n.data;}return {nodes:nodes,text:text};}
function charOn(m,i){for(var k=0;k<m.nodes.length;k++){var n=m.nodes[k][0],s=m.nodes[k][1];
if(i<s+n.data.length){var r=document.createRange();r.setStart(n,i-s);r.setEnd(n,i-s+1);return anyOn(r.getClientRects());}}return false;}
function edge(el,head){var m=map(el),rs=el.getClientRects(),len=m.text.length,at;
if(head){at=0;if(!on(rs[0])){for(var i2=0;i2<len;i2++){if(charOn(m,i2)){at=i2;break;}}}}
else{at=len;if(!on(rs[rs.length-1])){for(var i3=len-1;i3>=0;i3--){if(charOn(m,i3)){at=i3+1;break;}}}}
return {id:el.id,text:m.text,offset:at};}
return {first:edge(a,true),last:edge(z,false),visible:visible};
}"""

    /** The page [href] shows, from the script's answer; null when it gave none, or something else. */
    fun parse(href: String, raw: String?): PageProbe? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val first = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        // A script that answers with a string comes back quoted, once more.
        val parsed = if (first is JsonPrimitive && first.isString) runCatching { Json.parseToJsonElement(first.content) }.getOrNull() else first
        val root = parsed as? JsonObject ?: return null
        val visible = (root["visible"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return PageProbe(href, edge(root["first"]), edge(root["last"]), visible)
    }

    private fun edge(value: JsonElement?): PageEdge? {
        val item = value as? JsonObject ?: return null
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) ?: return null
        val text = (item["text"] as? JsonPrimitive)?.contentOrNull ?: return null
        val offset = (item["offset"] as? JsonPrimitive)?.intOrNull ?: return null
        return PageEdge(id, text, offset.coerceIn(0, text.length))
    }

}
