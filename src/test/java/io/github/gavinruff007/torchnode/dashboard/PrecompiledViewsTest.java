package io.github.gavinruff007.torchnode.dashboard;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class PrecompiledViewsTest {
 private String registry(){return PrecompiledViews.VIEWS.stream().sorted().map(name->"/WEB-INF/views/"+name+"=org.apache.jsp.WEB_002dINF.views."+name.replace(".","_")).reduce("",(a,b)->a+b+"\n");}
 private java.util.Map<String,String> read(String text)throws Exception{return PrecompiledViews.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
 @Test void completeRegistryAccepted()throws Exception{assertEquals(8,read(registry()).size());}
 @Test void missingViewFailsClosed(){assertThrows(IOException.class,()->read(registry().replaceAll("(?m)^.*topology.*\n","")));}
 @Test void duplicateViewFailsClosed(){assertThrows(IOException.class,()->read(registry()+registry().lines().findFirst().orElseThrow()+"\n"));}
 @Test void unknownViewFailsClosed(){assertThrows(IOException.class,()->read(registry()+"/other.jsp=org.apache.jsp.other_jsp\n"));}
 @Test void arbitraryClassFailsClosed(){assertThrows(IOException.class,()->read(registry().replace("org.apache.jsp.WEB_002dINF.views.admin_jsp","java.lang.Runtime")));}
 @Test void absentRegistryPreservesDynamicMode()throws Exception{assertFalse(PrecompiledViews.available());assertFalse(PrecompiledViews.install(null));}
}
