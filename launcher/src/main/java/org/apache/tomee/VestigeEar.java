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

import fr.gaellalire.maven_ear.BundleMapping;
import fr.gaellalire.maven_ear.MavenEAR;
import fr.gaellalire.vestige.spi.job.DummyJobHelper;
import fr.gaellalire.vestige.spi.resolver.ResolverException;
import fr.gaellalire.vestige.spi.resolver.VestigeJar;
import fr.gaellalire.vestige.spi.resolver.VestigeJarEntry;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContext;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContextBuilder;
import fr.gaellalire.vestige.spi.resolver.maven.ResolveMavenArtifactRequest;
import fr.gaellalire.vestige.spi.resolver.maven.ResolvedMavenArtifact;
import fr.gaellalire.vestige.spi.resolver.maven.VestigeMavenResolver;
import fr.gaellalire.vestige.vear.AdditionalRepository;
import fr.gaellalire.vestige.vear.Application;
import fr.gaellalire.vestige.vear.Config;
import fr.gaellalire.vestige.vear.MavenClassType;
import fr.gaellalire.vestige.vear.MavenConfig;
import fr.gaellalire.vestige.vear.ObjectFactory;

public class VestigeEar {

    private static ThreadLocal<VestigeMavenResolver> mavenResolver = new InheritableThreadLocal<>();

    public static void init(VestigeMavenResolver mavenResolver) {
        VestigeEar.mavenResolver.set(mavenResolver);
    }

    public static VestigeEar create(File vestigeWar) {
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

                // ModifyDependencyRequest modifyEARDependency =
                // vestigeMavenResolver.createMavenContextBuilder().addModifyDependency(groupId,
                // artifactId);

                ResolvedMavenArtifact resolvedMavenArtifact = resolve.execute(DummyJobHelper.INSTANCE);
                Map<String, Map<String, VestigeJar>> jarByArtifactIdByGroupId = new HashMap<String, Map<String, VestigeJar>>();
                List<ResolvedMavenArtifact> warResolvedMavenArtifacts = new ArrayList<>();
                for (ResolvedMavenArtifact dependency : Collections.list(resolvedMavenArtifact.getDependencies())) {
                    Map<String, VestigeJar> jarByArtifactId = jarByArtifactIdByGroupId.get(dependency.getGroupId());
                    if (jarByArtifactId == null) {
                        jarByArtifactId = new HashMap<String, VestigeJar>();
                        jarByArtifactIdByGroupId.put(dependency.getGroupId(), jarByArtifactId);
                    }
                    jarByArtifactId.put(dependency.getArtifactId(), dependency.getVestigeJar());

                    if ("war".equals(dependency.getExtension())) {
                        // modifyEARDependency.removeDependency(dependency.getGroupId(),
                        // dependency.getArtifactId(),
                        // dependency.getExtension());
                        warResolvedMavenArtifacts.add(dependency);
                    }
                }
                VestigeJar vestigeEAR = resolvedMavenArtifact.getVestigeJar();

                MavenEAR mavenEAR = null;
                VestigeJarEntry vestigeEAREntry = vestigeEAR.getEntry("META-INF/maven-ear.xml");
                if (vestigeEAREntry != null) {
                    try {
                        JAXBContext jc = JAXBContext.newInstance(fr.gaellalire.maven_ear.ObjectFactory.class.getPackage().getName());
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
                Map<String, VestigeJar> fileByName = new HashMap<String, VestigeJar>();
                for (BundleMapping bundleMapping : mavenEAR.getBundleMapping()) {
                    fileByName.put(bundleMapping.getBundleFileName(), jarByArtifactIdByGroupId.get(bundleMapping.getGroupId()).get(bundleMapping.getArtifactId()));
                }

                return new VestigeEar(vestigeEAR, fileByName);
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

    private Map<String, VestigeJar> fileByName;

    public VestigeEar(VestigeJar vestigeJar, Map<String, VestigeJar> fileByName) {
        this.vestigeJar = vestigeJar;
        this.fileByName = fileByName;
    }

    public URL getURL(String file) throws MalformedURLException {
        return fileByName.get(file).getFile().toURI().toURL();
    }

    public VestigeJar getVestigeJar() {
        return vestigeJar;
    }

}
