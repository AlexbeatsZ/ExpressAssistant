import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.item.TableString;
import java.io.*;
import java.nio.file.*;

public class MergeResources {
    public static void main(String[] args) throws Exception {
        TableBlock original=TableBlock.load(new File(args[0]));
        TableBlock own=TableBlock.load(new File(args[1]));
        if(original.getPackageBlockById(0x7f)==null || own.getPackageBlockById(0x7e)==null) throw new IllegalArgumentException("Unexpected resource package IDs");
        for(TableString item:own.getStringPool()) {
            String value=item.get();
            if(value!=null && value.startsWith("res/")) item.set("res/express/"+value.substring(4));
        }
        original.merge(own);
        original.refreshFull();
        if(original.size()!=2) throw new IllegalStateException("Expected two resource packages");
        original.writeBytes(new File(args[2]));
        System.out.println("resource_packages="+original.size());
    }
}
