package org.apache.tomee;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.maven.maven_ear.BundleMapping;
import org.apache.maven.maven_ear.MavenEAR;
import org.apache.tomee.vear.AdditionalRepository;
import org.apache.tomee.vear.Application;
import org.apache.tomee.vear.Config;
import org.apache.tomee.vear.MavenClassType;
import org.apache.tomee.vear.MavenConfig;
import org.apache.tomee.vear.ObjectFactory;

import fr.gaellalire.vestige.spi.job.DummyJobHelper;
import fr.gaellalire.vestige.spi.resolver.ResolvedClassLoaderConfiguration;
import fr.gaellalire.vestige.spi.resolver.ResolverException;
import fr.gaellalire.vestige.spi.resolver.Scope;
import fr.gaellalire.vestige.spi.resolver.VestigeJar;
import fr.gaellalire.vestige.spi.resolver.VestigeJarEntry;
import fr.gaellalire.vestige.spi.resolver.maven.CreateClassLoaderConfigurationRequest;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContext;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContextBuilder;
import fr.gaellalire.vestige.spi.resolver.maven.ModifyDependencyRequest;
import fr.gaellalire.vestige.spi.resolver.maven.ResolveMavenArtifactRequest;
import fr.gaellalire.vestige.spi.resolver.maven.ResolveMode;
import fr.gaellalire.vestige.spi.resolver.maven.ResolvedMavenArtifact;
import fr.gaellalire.vestige.spi.resolver.maven.VestigeMavenResolver;

public class VestigeEar {

    private static ThreadLocal<VestigeMavenResolver> mavenResolver = new InheritableThreadLocal<>();

    public static void init(final VestigeMavenResolver mavenResolver) {
        VestigeEar.mavenResolver.set(mavenResolver);
    }

    private Map<File, List<? extends VestigeJar>> dependenciesByWar;

    public static VestigeEar create(final File vestigeWar) {
        Unmarshaller unMarshaller = null;
        try {
            JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class.getPackage().getName());
            unMarshaller = jc.createUnmarshaller();

            URL xsdURL = VestigeEar.class.getResource("vear-1.0.0.xsd");
            SchemaFactory schemaFactory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
            Schema schema = schemaFactory.newSchema(xsdURL);
            unMarshaller.setSchema(schema);
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize settings parser", e);
        }

        Map<File, List<? extends VestigeJar>> dependenciesByWar = new HashMap<>();

        try {
            FileInputStream inputStream = new FileInputStream(vestigeWar);
            try {
                @SuppressWarnings("unchecked")
                Application value = ((JAXBElement<Application>) unMarshaller.unmarshal(inputStream)).getValue();

                MavenContextBuilder mavenContextBuilder = mavenResolver.get().createMavenContextBuilder();
                MavenClassType mavenResolver = value.getLauncher().getMavenResolver();
                Config configurations = value.getConfigurations();
                if (configurations != null) {
                    MavenConfig mavenConfig = configurations.getMavenConfig();
                    if (mavenConfig != null) {
                        List<Object> modifyDependencyOrReplaceDependencyOrAdditionalRepository = mavenConfig.getModifyDependencyOrReplaceDependencyOrAdditionalRepository();
                        for (Object object : modifyDependencyOrReplaceDependencyOrAdditionalRepository) {
                            if (object instanceof AdditionalRepository) {
                                AdditionalRepository additionalRepository = (AdditionalRepository) object;
                                mavenContextBuilder.addAdditionalRepository(additionalRepository.getId(), additionalRepository.getLayout(), additionalRepository.getUrl());
                            }
                        }
                    }
                }

                MavenContext build = mavenContextBuilder.build();
                ResolveMavenArtifactRequest resolve = build.resolve(mavenResolver.getGroupId(), mavenResolver.getArtifactId(), mavenResolver.getVersion());

                resolve.setExtension("ear");

                ModifyDependencyRequest modifyEARDependency = VestigeEar.mavenResolver.get().createMavenContextBuilder().addModifyDependency(mavenResolver.getGroupId(),
                        mavenResolver.getArtifactId());

                ResolvedMavenArtifact resolvedMavenArtifact = resolve.execute(DummyJobHelper.INSTANCE);
                Map<String, Map<String, URL>> jarByArtifactIdByGroupId = new HashMap<String, Map<String, URL>>();
                List<ResolvedMavenArtifact> warResolvedMavenArtifacts = new ArrayList<>();
                for (ResolvedMavenArtifact dependency : Collections.list(resolvedMavenArtifact.getDependencies())) {
                    Map<String, URL> jarByArtifactId = jarByArtifactIdByGroupId.get(dependency.getGroupId());
                    if (jarByArtifactId == null) {
                        jarByArtifactId = new HashMap<String, URL>();
                        jarByArtifactIdByGroupId.put(dependency.getGroupId(), jarByArtifactId);
                    }

                    File file = dependency.getVestigeJar().getFile();
                    if ("war".equals(dependency.getExtension())) {
                        modifyEARDependency.removeDependency(dependency.getGroupId(), dependency.getArtifactId(), dependency.getExtension());
                        warResolvedMavenArtifacts.add(dependency);

                        ResolveMavenArtifactRequest resolveWar = build.resolve(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                        resolveWar.setExtension("war");
                        ResolvedMavenArtifact warResolvedMavenArtifact = resolveWar.execute(DummyJobHelper.INSTANCE);

                        // unzip
                        warResolvedMavenArtifact.getVestigeJar().getFile();

                        // don't care about option, we will not attach the
                        // classloader ATM
                        CreateClassLoaderConfigurationRequest createClassLoaderConfigurationRequest = warResolvedMavenArtifact.createClassLoaderConfiguration("",
                                ResolveMode.CLASSPATH, Scope.PLATFORM);
                        createClassLoaderConfigurationRequest.setSelfExcluded(true);
                        ResolvedClassLoaderConfiguration resolvedClassLoaderConfiguration = createClassLoaderConfigurationRequest.execute();

                        dependenciesByWar.put(file, Collections.list(resolvedClassLoaderConfiguration.getVestigeJarEnumeration()));
                    }
                    jarByArtifactId.put(dependency.getArtifactId(), file.toURI().toURL());
                }
                VestigeJar vestigeEAR = resolvedMavenArtifact.getVestigeJar();

                MavenEAR mavenEAR = null;
                VestigeJarEntry vestigeEAREntry = vestigeEAR.getEntry("META-INF/maven-ear.xml");
                if (vestigeEAREntry != null) {
                    try {
                        JAXBContext jc = JAXBContext.newInstance(org.apache.maven.maven_ear.ObjectFactory.class.getPackage().getName());
                        unMarshaller = jc.createUnmarshaller();

                        URL xsdURL = VestigeEar.class.getResource("maven-ear-1.0.0.xsd");
                        SchemaFactory schemaFactory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
                        Schema schema = schemaFactory.newSchema(xsdURL);
                        unMarshaller.setSchema(schema);
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to initialize settings parser", e);
                    }
                    mavenEAR = ((JAXBElement<MavenEAR>) unMarshaller.unmarshal(vestigeEAREntry.open())).getValue();
                }
                Map<String, URL> fileByName = new HashMap<String, URL>();
                for (BundleMapping bundleMapping : mavenEAR.getBundleMapping()) {
                    fileByName.put(bundleMapping.getBundleFileName(), jarByArtifactIdByGroupId.get(bundleMapping.getGroupId()).get(bundleMapping.getArtifactId()));
                }

                return new VestigeEar(vestigeEAR, fileByName, dependenciesByWar);
            } finally {
                inputStream.close();
            }
        } catch (ResolverException e) {
            throw new RuntimeException("Unable to resolve vear file", e);
        } catch (JAXBException e) {
            throw new RuntimeException("Unable to read vear file", e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read vear file", e);
        }
    }

    private VestigeJar vestigeJar;

    private Map<String, URL> fileByName;

    public VestigeEar(final VestigeJar vestigeJar, final Map<String, URL> fileByName, final Map<File, List<? extends VestigeJar>> dependenciesByWar) {
        this.vestigeJar = vestigeJar;
        this.fileByName = fileByName;
        this.dependenciesByWar = dependenciesByWar;
    }

    public URL getURL(final String file) throws MalformedURLException {
        return fileByName.get(file);
    }

    public List<? extends VestigeJar> getWarDependencies(final File file) {
        return dependenciesByWar.get(file);
    }

    public VestigeJar getVestigeJar() {
        return vestigeJar;
    }

}
