package org.apache.tomee;

import java.io.File;
import java.lang.reflect.Field;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.apache.catalina.Globals;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.webresources.TomcatURLStreamHandlerFactory;
import org.apache.openejb.core.security.AbstractSecurityService;
import org.apache.openejb.loader.Files;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.resource.GeronimoTransactionManagerFactory;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.modeler.BaseModelMBean;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomee.catalina.TomEEClassLoaderEnricher;

import com.sun.xml.bind.v2.bytecode.ClassTailor;

import fr.gaellalire.vestige.spi.resolver.maven.VestigeMavenResolver;

/**
 * @author gaellalire
 */
public class TomEEVestigeLauncher implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(TomEEVestigeLauncher.class.getName());
    
    private VestigeMavenResolver mavenResolver;

    public void setMavenResolver(final VestigeMavenResolver mavenResolver) {
        this.mavenResolver = mavenResolver;
    }

    public void setVestigeSystem(final TomEEVestigeSystem vestigeSystem) {
        TomcatURLStreamHandlerFactory.disable();
        vestigeSystem.setURLStreamHandlerFactory(TomcatURLStreamHandlerFactory.getInstance());
        vestigeSystem.setOut(new SystemLogHandler(vestigeSystem.getOut()));
        vestigeSystem.setErr(new SystemLogHandler(vestigeSystem.getErr()));
        if (System.getSecurityManager() != null) {
            // policy activated
            vestigeSystem.setPolicy(new Policy() {

                private Map<CodeSource, Permissions> permissionsByCodeSource = new HashMap<CodeSource, Permissions>();

                @Override
                public PermissionCollection getPermissions(final CodeSource codesource) {
                    Permissions permissions = permissionsByCodeSource.get(codesource);
                    if (permissions == null || permissions.isReadOnly()) {
                        permissions = new Permissions();
                        permissionsByCodeSource.put(codesource, permissions);
                    }
                    return permissions;
                }

                @Override
                public boolean implies(final ProtectionDomain domain, final Permission permission) {
                    // all permissions
                    return true;
                }

            });
        }
        AbstractSecurityService.vestigeSystem = vestigeSystem;
    }

    public TomEEVestigeLauncher(final File base, final File data) {
        // avoid call to URL.setURLStreamHandlerFactory
        try {
            Field declaredField = WebappLoader.class.getDeclaredField("first");
            declaredField.setAccessible(true);
            declaredField.set(null, false);
            declaredField.setAccessible(false);
        } catch (Exception e) {
        }
        System.setProperty(TomEEClassLoaderEnricher.TOMEE_WEBAPP_CLASSLOADER_ENRICHMENT_SKIP, "true");
        System.setProperty(Globals.CATALINA_BASE_PROP, base.getPath());
        System.setProperty(Globals.CATALINA_HOME_PROP, base.getPath());
        System.setProperty(ClassTailor.class.getName()+".noOptimize", "true");
    }

    private volatile boolean started = false;

    public void run() {
        VestigeWar.init(mavenResolver);

        final Catalina catalina = new Catalina() {
            @Override
            protected void initStreams() {
                // system.out & system.err are already hooked
            }
        };
        catalina.setUseShutdownHook(false);
        catalina.setParentClassLoader(TomEEVestigeLauncher.class.getClassLoader());

        Thread launcherThread = new Thread() {
            @Override
            public void run() {
                catalina.start();
                started = true;
            }
        };
        launcherThread.start();
        try {
            synchronized (this) {
                wait();
            }
        } catch (InterruptedException e) {
            while (true) {
                try {
                    launcherThread.join();
                    break;
                } catch (InterruptedException e1) {
                    LOGGER.log(Level.FINE, "Ignore interrupt", e1);
                }
            }
            if (started) {
                catalina.stop();
            }
            Files.delete();

            List<MBeanServer> mBeanServers = Arrays.asList(Registry.getRegistry(null, null).getMBeanServer(), LocalMBeanServer.get());

            for (MBeanServer mBeanServer : mBeanServers) {
                List<ObjectName> toRemove = new ArrayList<ObjectName>();

                for (ObjectInstance o : mBeanServer.queryMBeans(null, null)) {
                    if (o.getClassName().equals(BaseModelMBean.class.getName())) {
                        toRemove.add(o.getObjectName());
                    }
                    if (o.getClassName().equals(GeronimoTransactionManagerFactory.TransactionManagerMBean.class.getName())) {
                        toRemove.add(o.getObjectName());
                    }
                }
                for (ObjectName name : toRemove) {
                    try {
                        mBeanServer.unregisterMBean(name);
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }
            }

            Thread currentThread = Thread.currentThread();
            ThreadGroup threadGroup = currentThread.getThreadGroup();
            int activeCount = threadGroup.activeCount();
            while (activeCount != 1) {
                Thread[] list = new Thread[activeCount];
                int enumerate = threadGroup.enumerate(list);
                for (int i = 0; i < enumerate; i++) {
                    Thread t = list[i];
                    if (t == currentThread) {
                        continue;
                    }
                    t.interrupt();
                }
                for (int i = 0; i < enumerate; i++) {
                    Thread t = list[i];
                    if (t == currentThread) {
                        continue;
                    }
                    try {
                        t.join();
                    } catch (InterruptedException e1) {
                        LOGGER.log(Level.FINE, "Interrupted", e1);
                        break;
                    }
                }
                activeCount = threadGroup.activeCount();
            }

            // StatusManagerServlet.destroy should call mBeanServer.removeNotificationListener
        } finally {
            started = false;
        }
    }

}
