import pxb.android.axml.*;
import java.nio.file.*;
import java.util.*;

public class MergeManifest {
    static final String ANDROID="http://schemas.android.com/apk/res/android";
    static Object attr(Axml.Node node,String name) {
        for(Axml.Node.Attr a:node.attrs) if(name.equals(a.name)) return a.value;
        return null;
    }
    static void set(Axml.Node node,String name,int id,int type,Object value) {
        node.attrs.removeIf(a -> name.equals(a.name)); node.attr(ANDROID,name,id,type,value);
    }
    static Axml.Node child(Axml.Node n,String name) {
        for(Axml.Node c:n.children) if(name.equals(c.name)) return c;
        return null;
    }
    static void absoluteNames(Axml.Node n,String pkg) {
        if(Set.of("application","activity","activity-alias","service","receiver","provider").contains(n.name)) {
            for(Axml.Node.Attr a:n.attrs) if("name".equals(a.name)&&a.value instanceof String&&((String)a.value).startsWith(".")) a.value=pkg+a.value;
        }
        for(Axml.Node c:n.children) absoluteNames(c,pkg);
    }
    static void rewriteIdentifiers(Axml.Node n,String old) {
        for(Axml.Node.Attr a:n.attrs) if(a.value instanceof String) {
            boolean identifier=Set.of("permission","readPermission","writePermission","authorities","taskAffinity","process").contains(a.name)
                || (Set.of("permission","uses-permission").contains(n.name)&&"name".equals(a.name));
            if(identifier) a.value=((String)a.value).replace(old,"com.halo.expressassistant");
        }
        for(Axml.Node c:n.children) rewriteIdentifiers(c,old);
    }
    public static void main(String[] args) throws Exception {
        Axml original=new Axml(); new AxmlReader(Files.readAllBytes(Path.of(args[0]))).accept(original);
        Axml.Node root=original.firsts.get(0); String old=(String)attr(root,"package");
        absoluteNames(root,old);
        rewriteIdentifiers(root,old);
        for(Axml.Node.Attr a:root.attrs) if("package".equals(a.name)) a.value="com.halo.expressassistant";

        if(args.length>2) {
            Axml own=new Axml();new AxmlReader(Files.readAllBytes(Path.of(args[2]))).accept(own);
            Axml.Node ownRoot=own.firsts.get(0), app=child(root,"application"), ownApp=child(ownRoot,"application");
            set(root,"versionCode",0x0101021b,NodeVisitor.TYPE_INT_HEX,attr(ownRoot,"versionCode"));
            set(root,"versionName",0x0101021c,NodeVisitor.TYPE_STRING,attr(ownRoot,"versionName"));
            for(Axml.Node c:root.children) if("application".equals(c.name)) {
                for(Axml.Node component:c.children) component.children.removeIf(x -> "intent-filter".equals(x.name)&&x.children.stream().anyMatch(v -> "category".equals(v.name)&&"android.intent.category.LAUNCHER".equals(attr(v,"name"))));
            }
            Set<String> names=new HashSet<>(); for(Axml.Node n:root.children) if(attr(n,"name")!=null) names.add(n.name+attr(n,"name"));
            for(Axml.Node n:ownRoot.children) {
                if(Set.of("uses-permission","permission","uses-feature","queries").contains(n.name)&&names.add(n.name+attr(n,"name"))) root.children.add(n);
            }
            for(String key:Set.of("icon","roundIcon","label","supportsRtl","enableOnBackInvokedCallback")) {
                for(Axml.Node.Attr a:ownApp.attrs) if(key.equals(a.name)) {app.attrs.removeIf(x -> key.equals(x.name));app.attrs.add(a);break;}
            }
            for(Axml.Node n:ownApp.children) {
                if(!Set.of("activity","receiver","service","provider").contains(n.name)) continue;
                if("androidx.startup.InitializationProvider".equals(attr(n,"name"))) continue;
                for(Axml.Node.Attr a:n.attrs) if("enabled".equals(a.name)&&a.type==NodeVisitor.TYPE_REFERENCE) {
                    a.type=NodeVisitor.TYPE_INT_BOOLEAN; a.value=!"androidx.work.impl.background.systemalarm.SystemAlarmService".equals(attr(n,"name"));
                }
                app.children.add(n);
            }
        }
        AxmlWriter writer=new AxmlWriter(); original.accept(writer);Files.write(Path.of(args[1]),writer.toByteArray());
    }
}
