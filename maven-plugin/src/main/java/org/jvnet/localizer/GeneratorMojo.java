package org.jvnet.localizer;

import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JVar;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.FileSet;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * @author Kohsuke Kawaguchi
 * @goal generate
 * @phase generate-sources
 */
public class GeneratorMojo extends AbstractMojo {

    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * The directory to place generated property files.
     *
     * @parameter default-value="${project.build.directory}/generated-sources"
     * @required
     * @readonly
     */
    protected File outputDirectory;

    /**
     * Additional file name mask like "Messages.properties" to further
     * restrict the resource processing.
     *
     * @parameter
     */
    protected String fileMask;

    private JCodeModel cm;

    public void execute() throws MojoExecutionException, MojoFailureException {

        cm = new JCodeModel();


        for(Resource res : (List<Resource>)project.getResources()) {
            File baseDir = new File(res.getDirectory());

            FileSet fs = new FileSet();
            fs.setDir(baseDir);
            for( String name : (List<String>)res.getIncludes() )
                fs.createInclude().setName(name);
            for( String name : (List<String>)res.getExcludes() )
                fs.createExclude().setName(name);

            for( String relPath : fs.getDirectoryScanner(new Project()).getIncludedFiles() ) {
                File f = new File(baseDir,relPath);
                if(!f.getName().endsWith(".properties") || f.getName().contains("_"))
                    continue;
                if(fileMask!=null && !f.getName().equals(fileMask))
                    continue;

                try {
                    generate(f,relPath);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to generate a class from "+f,e);
                }
            }
        }

        try {
            outputDirectory.mkdirs();
            cm.build(outputDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate source files",e);
        }

        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    }

    private void generate(File propertyFile, String relPath) throws IOException {
        String className = toClassName(relPath);

        // up to date check
        File sourceFile = new File(outputDirectory,className.replace('.','/')+".java");
        if(sourceFile.exists() && sourceFile.lastModified()>propertyFile.lastModified()) {
            getLog().debug(sourceFile+" is up to date");
            return;
        }

        // go generate one
        Properties props = new Properties();
        FileInputStream in = new FileInputStream(propertyFile);
        try {
            props.load(in);
        } catch (IOException e) {
            in.close();
        }

        try {
            JDefinedClass c = cm._class(className);

            // [RESULT]
            // private static final ResourceBundleHolder holder = new BundleHolder(Messages.class);

            JVar holder = c.field(JMod.PRIVATE | JMod.STATIC | JMod.FINAL, ResourceBundleHolder.class, "holder",
                    JExpr._new(cm.ref(ResourceBundleHolder.class)).arg(c.dotclass()) );


            for (Entry<Object,Object> e : props.entrySet()) {
                String key = e.getKey().toString();
                String value = e.getValue().toString();

                int n = countArgs(value);

                // generate the default format method
                List<JVar> args = new ArrayList<JVar>();
                JMethod m = c.method(JMod.PUBLIC | JMod.STATIC, cm.ref(String.class), toJavaIdentifier(key));
                for( int i=1; i<=n; i++ )
                    args.add(m.param(Object.class,"arg"+i));

                JInvocation inv = holder.invoke("format").arg(key);
                for (JVar arg : args)
                    inv.arg(arg);
                m.body()._return(inv);

                m.javadoc().add(value);

                // generate localizable factory
                args.clear();
                m = c.method(JMod.PUBLIC | JMod.STATIC, cm.ref(Localizable.class), '_'+toJavaIdentifier(key));
                for( int i=1; i<=n; i++ )
                    args.add(m.param(Object.class,"arg"+i));

                inv = JExpr._new(cm.ref(Localizable.class)).arg(holder).arg(key);
                for (JVar arg : args)
                    inv.arg(arg);
                m.body()._return(inv);

                m.javadoc().add(value);
            }

        } catch (JClassAlreadyExistsException e) {
            throw new AssertionError(e);
        }

    }

    /**
     * Counts the number of arguments.
     */
    private int countArgs(String formatString) {
        List<String> args = new ArrayList<String>();
        String lastStr = formatString;

        while(true) {
            args.add("xxx");
            String s = MessageFormat.format(formatString, args.toArray());
            if(s.equals(lastStr))
                return args.size()-1;
            lastStr = s;
        }
    }

    private String toJavaIdentifier(String key) {
        // TODO: this is fairly dumb implementation
        return key.replace('.','_');
    }

    private String toClassName(String relPath) {
        relPath = relPath.substring(0,relPath.length()-".properties".length());
        return relPath.replace(File.separatorChar,'.');
    }
}
