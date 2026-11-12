// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.search;

import java.util.*;
import java.util.regex.*;

/** Lexical retrieval helpers, not a BSL compiler or semantic similarity model. */
final class Bm25Documents {
    static final int MAX_TOKENS=12000,MAX_TERMS=2048;
    static final Pattern WORD=Pattern.compile("[\\p{L}\\p{N}_]+"),CAMEL=Pattern.compile("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|_+");
    static final Set<String> STOP=Set.of("и","в","на","с","из","для","по","к","от","до","за","при","как","что","это","или","а","но","не","нет","есть","то","же","the","a","an","of","to","for","in","and","or");
    static final List<String> ENDINGS=List.of("иями","ями","ами","ого","ему","ому","ыми","ими","иях","ах","ях","ов","ев","ом","ем","ам","ям","ий","ый","ая","ое","ие","ые","ой","ей","ы","и","а","я","у","ю","е","о","ь");
    static List<String> tokens(String text,int max) {
        List<String> out=new ArrayList<>();Matcher words=WORD.matcher(text);
        while(words.find()&&out.size()<max){String word=words.group();if(word.length()>128)continue;Set<String> local=new LinkedHashSet<>();local.add(normalize(word));for(String part:CAMEL.split(word))local.add(normalize(part));for(String term:local)if(!term.isBlank()&&!STOP.contains(term)&&out.size()<max)out.add(term);}
        return out;
    }
    static String normalize(String text){String s=text.toLowerCase(Locale.ROOT).replace('ё','е');if(s.matches("[а-я]+")&&s.length()>5)for(String end:ENDINGS)if(s.endsWith(end)&&s.length()-end.length()>=4)return s.substring(0,s.length()-end.length());return s;}
    record Spec(String method,String kind,int start,int declaration,int end,String content){}
    private static final Pattern OPEN=Pattern.compile("(?iu)^\\s*(процедура|функция|procedure|function)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\("),CLOSE=Pattern.compile("(?iu)^\\s*(конецпроцедуры|конецфункции|endprocedure|endfunction)\\b");
    static List<Spec> chunks(String text) {
        String[] lines=text.split("\\R",-1);List<Spec> out=new ArrayList<>();boolean quoted=false;int start=-1,decl=-1,globalStart=0;String name="";
        for(int i=0;i<lines.length;i++){
            String line=lines[i];boolean outside=!quoted;Matcher open=OPEN.matcher(line);
            if(outside&&open.find()){
                if(start>=0)out.add(spec(name,"method",start,decl,i-1,lines));
                int comment=i;while(comment>globalStart&&i-comment<16&&(lines[comment-1].isBlank()||lines[comment-1].stripLeading().startsWith("//")||lines[comment-1].stripLeading().startsWith("&")))comment--;
                if(start<0&&comment>globalStart&&hasCode(lines,globalStart,comment-1))out.add(spec("","module",globalStart,globalStart,comment-1,lines));
                start=comment;decl=i;name=open.group(2);
            }
            if(outside&&CLOSE.matcher(line).find()&&start>=0){out.add(spec(name,"method",start,decl,i,lines));start=-1;decl=-1;globalStart=i+1;name="";}
            quoted=quoteState(line,quoted);
        }
        if(start>=0)out.add(spec(name,"method",start,decl,lines.length-1,lines));
        else if(globalStart<lines.length&&hasCode(lines,globalStart,lines.length-1))out.add(spec("","module",globalStart,globalStart,lines.length-1,lines));
        return out;
    }
    private static boolean hasCode(String[] lines,int from,int to){for(int i=from;i<=to;i++)if(!lines[i].isBlank())return true;return false;}
    private static Spec spec(String name,String kind,int start,int decl,int end,String[] lines){return new Spec(name,kind,start+1,decl+1,end+1,String.join("\n",Arrays.copyOfRange(lines,start,end+1)));}
    static boolean quoteState(String line,boolean quoted){for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){i++;continue;}quoted=!quoted;}else if(!quoted&&c=='/'&&i+1<line.length()&&line.charAt(i+1)=='/')break;}return quoted;}
    static String caption(String text){Matcher open=Pattern.compile("(?i)<(synonym|comment|description)>").matcher(text);StringBuilder b=new StringBuilder();int cursor=0;while(cursor<text.length()&&b.length()<4000&&open.find(cursor)){int from=open.end(),upper=Math.min(text.length(),from+16000);Matcher close=Pattern.compile("(?i)</"+open.group(1)+">").matcher(text);close.region(from,upper);if(close.find()){b.append(text.substring(from,close.start()).replaceAll("<[^>]{1,500}>"," ")).append(' ');cursor=close.end();}else cursor=upper;}return b.substring(0,Math.min(b.length(),4000)).replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");}
}
