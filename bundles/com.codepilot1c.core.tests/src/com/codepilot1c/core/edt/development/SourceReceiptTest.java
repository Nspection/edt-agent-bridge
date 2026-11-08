// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
public class SourceReceiptTest {
 @Test public void includesCleanFilesAndDetectsContentChangesWithoutGit()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
  Path p=f.path.resolve("src");Files.createDirectories(p);Files.writeString(p.resolve("A.bsl"),"old");var a=SourceReceipt.capture(f.path);assertEquals(true,a.get("complete"));
  Files.writeString(f.path.resolve("not-a-source.log"),"ignored");assertEquals(a,SourceReceipt.capture(f.path));
  Files.writeString(p.resolve("A.bsl"),"new");var b=SourceReceipt.capture(f.path);assertNotEquals(a.get("sha256"),b.get("sha256"));assertEquals("changed",SourceReceipt.compare(a,a,b,b));
  Files.writeString(p.resolve("B.bsl"),"new file");assertNotEquals(b.get("sha256"),SourceReceipt.capture(f.path).get("sha256"));
 }}
 @Test public void incompleteNeverBecomesMatchedAndLargeFilesAreBounded()throws Exception{try(var f=new TestDataLedgerTest.Fixture()){
  Files.createDirectories(f.path.resolve("src"));try(var file=new java.io.RandomAccessFile(f.path.resolve("src/large.bin").toFile(),"rw")){file.setLength(17L*1024*1024);}
  var missing=SourceReceipt.capture(f.path);assertEquals(false,missing.get("complete"));assertEquals("unknown_incomplete_receipt",SourceReceipt.compare(missing,missing,missing,missing));
 }}
 @Test public void matchingReceiptsDoNotCertifyContinuousOrBusinessCoverage(){var a=Map.<String,Object>of("complete",true,"sha256","a","project_location","p");assertEquals("matched_at_checkpoints",SourceReceipt.compare(a,a,a,a));var b=Map.<String,Object>of("complete",true,"sha256","a","project_location","moved");assertEquals("changed",SourceReceipt.compare(a,a,a,b));}
}
