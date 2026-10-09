package com.pocketds.hub.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The words of a page for the controller's cursor (#62): what the web view reports, and what it is asked to draw and select. The page
 * script walks the document's text once (every run of letters between spaces is a word, numbered in the document's order and put in
 * the block it is in), and reports the words of every paragraph that has any part on the page, with where each is. [TextCursor] does
 * the moving; the script only draws the cursor's box and puts the page's own selection over the words chosen, so that
 * `currentSelection()` and the card work on a cursor's selection exactly as they do on a finger's.
 */
object ReaderWordsScript {
    /** The page's words; install is idempotent, and every script below begins with it. */
    private const val INSTALL = """
var pd=window.__pdWords;
if(!pd||pd.length!==document.body.textContent.length){
  var BLOCK={P:1,DIV:1,LI:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,BLOCKQUOTE:1,TD:1,TH:1,FIGCAPTION:1,DT:1,DD:1,PRE:1,SECTION:1,ARTICLE:1,ASIDE:1,BODY:1};
  var words=[],blocks=[],paragraphs=[],walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
    var p=n.parentElement;while(p&&p!==document.body){var t=p.tagName.toUpperCase();if(t==='SCRIPT'||t==='STYLE'||t==='RT'||t==='RP')return NodeFilter.FILTER_REJECT;p=p.parentElement;}
    return NodeFilter.FILTER_ACCEPT;}}),node;
  // An XHTML page keeps its tag names as written, in lower case: they are compared as upper case, as an HTML page's are.
  function blockOf(n){var e=n.parentElement;while(e&&!BLOCK[e.tagName.toUpperCase()])e=e.parentElement;return e||document.body;}
  while((node=walker.nextNode())){
    var text=node.nodeValue,re=/\S+/g,m,b=blockOf(node),at=blocks.indexOf(b);
    if(at<0){at=blocks.length;blocks.push(b);paragraphs.push([]);}
    while((m=re.exec(text))){words.push({node:node,start:m.index,end:m.index+m[0].length,text:m[0],para:at});paragraphs[at].push(words.length-1);}
  }
  pd=window.__pdWords={length:document.body.textContent.length,words:words,blocks:blocks,paragraphs:paragraphs};
}
function range(i,j){var r=document.createRange();r.setStart(pd.words[i].node,pd.words[i].start);r.setEnd(pd.words[j].node,pd.words[j].end);return r;}
"""

    /** The words of every paragraph with a part on the page, each with its number, text, paragraph, box and whether it is on the page. */
    val PAGE: String = """(function(){$INSTALL
var W=window.innerWidth,H=window.innerHeight,out=[],seen=[],lo=-1,hi=-1;
function on(r){return r.width>0&&r.height>0&&r.right>0&&r.left<W&&r.bottom>0&&r.top<H;}
for(var p=0;p<pd.blocks.length;p++){
  var rects=pd.blocks[p].getClientRects(),hit=false;
  for(var k=0;k<rects.length;k++){if(rects[k].right>0&&rects[k].left<W&&rects[k].bottom>0&&rects[k].top<H){hit=true;break;}}
  seen.push(hit);if(hit){if(lo<0)lo=p;hi=p;}
}
if(lo<0){lo=0;hi=pd.blocks.length-1;}
// The paragraph before the page and the one after it come too, off the page, so the cursor always has a next word to turn to.
for(var p2=Math.max(0,lo-1);p2<=Math.min(pd.blocks.length-1,hi+1);p2++){
  var any=false,list=[];
  for(var q=0;q<pd.paragraphs[p2].length;q++){
    var i=pd.paragraphs[p2][q],r=null;
    if(seen[p2]){var rs=range(i,i).getClientRects();r=rs.length?rs[0]:null;}
    var vis=r&&on(r)?1:0;if(vis)any=true;
    list.push([i,pd.words[i].text,p2,r?r.left:0,r?r.top:0,r?r.right:0,r?r.bottom:0,vis]);
  }
  if(any||!seen[p2])for(var z=0;z<list.length;z++)out.push(list[z]);
}
return {total:pd.words.length,words:out};
})()"""

    /** Draws the cursor on word [cursor], and the selection from [anchor] to it as the page's own selection (the anchor is -1 for none). */
    fun draw(cursor: Int, anchor: Int, accent: Int): String = """(function(){$INSTALL
var c=$cursor,a=$anchor,box=document.getElementById('__pd-cursor');
if(!box){box=document.createElement('div');box.id='__pd-cursor';box.style.cssText='position:absolute;pointer-events:none;z-index:2147483000;border-radius:3px;';document.body.appendChild(box);}
if(c<0||c>=pd.words.length){box.style.display='none';return 'none';}
var r=range(c,c).getClientRects()[0];
if(!r){box.style.display='none';return 'hidden';}
box.style.display='block';box.style.left=(r.left+window.scrollX-2)+'px';box.style.top=(r.top+window.scrollY)+'px';box.style.width=(r.width+4)+'px';box.style.height=r.height+'px';
box.style.borderBottom='3px solid ${rgb(accent)}';box.style.background='${rgba(accent, 0.28)}';
var s=window.getSelection();
if(a>=0&&a<pd.words.length){var lo=Math.min(a,c),hi=Math.max(a,c);s.removeAllRanges();s.addRange(range(lo,hi));}
else s.removeAllRanges();
return 'ok';
})()"""

    /** Selects the words [first] to [last] as the page's own selection and puts the cursor's box away: what a finger's long press leaves. */
    fun select(first: Int, last: Int): String = """(function(){$INSTALL
var box=document.getElementById('__pd-cursor');if(box)box.style.display='none';
if($first<0||$last>=pd.words.length)return 'none';
var s=window.getSelection();s.removeAllRanges();s.addRange(range($first,$last));return 'ok';
})()"""

    /** Puts the cursor's box and the selection away. */
    const val CLEAR = """(function(){var box=document.getElementById('__pd-cursor');if(box)box.parentNode.removeChild(box);window.getSelection().removeAllRanges();return 'ok';})()"""

    private fun rgb(color: Int) = ReadAlongGlow.rgb(color)

    private fun rgba(color: Int, alpha: Double) = "rgba(${(color shr 16) and 0xFF}, ${(color shr 8) and 0xFF}, ${color and 0xFF}, $alpha)"

    /** The words from the script's answer; null when it gave none, or something else. */
    fun parse(raw: String?): List<PageWord>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val first = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val parsed = if (first is JsonPrimitive && first.isString) runCatching { Json.parseToJsonElement(first.content) }.getOrNull() else first
        val rows = (parsed as? JsonObject)?.get("words") as? JsonArray ?: return null
        return rows.mapNotNull { row ->
            val cells = row as? JsonArray ?: return@mapNotNull null
            if (cells.size < 8) return@mapNotNull null
            fun text(at: Int) = (cells[at] as? JsonPrimitive)?.contentOrNull
            fun number(at: Int) = (cells[at] as? JsonPrimitive)?.floatOrNull
            PageWord(
                index = (cells[0] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                text = text(1) ?: return@mapNotNull null,
                paragraph = (cells[2] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                left = number(3) ?: return@mapNotNull null, top = number(4) ?: return@mapNotNull null,
                right = number(5) ?: return@mapNotNull null, bottom = number(6) ?: return@mapNotNull null,
                visible = (cells[7] as? JsonPrimitive)?.intOrNull == 1
            )
        }.sortedBy { it.index }
    }
}
