// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.forms;
import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.resources.IFile;
public class ModuleFileReadClosureTest {
 private IFile file(InputStream stream){return (IFile)Proxy.newProxyInstance(IFile.class.getClassLoader(),new Class<?>[]{IFile.class},(proxy,method,args)->{if(method.getName().equals("getContents"))return stream;throw new UnsupportedOperationException(method.getName());});}
 @Test public void releasesFileHandleAfterSuccessfulRead()throws Exception{
  AtomicBoolean closed=new AtomicBoolean();var stream=new ByteArrayInputStream("BSL".getBytes()){@Override public void close()throws IOException{closed.set(true);super.close();}};
  assertEquals("BSL",ModuleFileWriter.readUtf8Contents(file(stream)));assertTrue(closed.get());
 }
 @Test public void releasesFileHandleWhenReadingFails()throws Exception{
  AtomicBoolean closed=new AtomicBoolean();var stream=new InputStream(){@Override public int read()throws IOException{throw new IOException("simulated read failure");}@Override public void close(){closed.set(true);}};
  try{ModuleFileWriter.readUtf8Contents(file(stream));fail();}catch(IOException expected){assertEquals("simulated read failure",expected.getMessage());}assertTrue(closed.get());
 }
}
