package org.apache.tomee;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import fr.gaellalire.vestige.spi.job.DummyJobHelper;
import fr.gaellalire.vestige.spi.resolver.ResolvedClassLoaderConfiguration;
import fr.gaellalire.vestige.spi.resolver.ResolverException;
import fr.gaellalire.vestige.spi.resolver.Scope;
import fr.gaellalire.vestige.spi.resolver.VestigeJar;
import fr.gaellalire.vestige.spi.resolver.maven.CreateClassLoaderConfigurationRequest;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContext;
import fr.gaellalire.vestige.spi.resolver.maven.MavenContextBuilder;
import fr.gaellalire.vestige.spi.resolver.maven.ResolveMavenArtifactRequest;
import fr.gaellalire.vestige.spi.resolver.maven.ResolveMode;
import fr.gaellalire.vestige.spi.resolver.maven.ResolvedMavenArtifact;
import fr.gaellalire.vestige.spi.resolver.maven.VestigeMavenResolver;
import fr.gaellalire.vestige.vwar.AdditionalRepository;
import fr.gaellalire.vestige.vwar.Application;
import fr.gaellalire.vestige.vwar.Config;
import fr.gaellalire.vestige.vwar.MavenClassType;
import fr.gaellalire.vestige.vwar.MavenConfig;
import fr.gaellalire.vestige.vwar.ObjectFactory;

public class VestigeWar {

    private static ThreadLocal<VestigeMavenResolver> mavenResolver = new InheritableThreadLocal<>();

    private static ThreadLocal<File> unpackDir = new InheritableThreadLocal<>();

    public static void init(final VestigeMavenResolver mavenResolver, final File unpackDir) {
        VestigeWar.mavenResolver.set(mavenResolver);
        VestigeWar.unpackDir.set(unpackDir);
    }

    public static File getUnpackDir() {
        return unpackDir.get();
    }

    public static Marshaller getMarshaller() {
        Marshaller marshaller = null;
        try {
            JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class.getPackage().getName());
            marshaller = jc.createMarshaller();

            URL xsdURL = VestigeWar.class.getResource("vwar-1.0.0.xsd");
            SchemaFactory schemaFactory = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
            Schema schema = schemaFactory.newSchema(xsdURL);
            marshaller.setSchema(schema);
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize settings parser", e);
        }
        return marshaller;
    }

    public static void createApplication(final String groupId, final String artifactId) {

    }

    public static VestigeWar create(final File vestigeWar, final String baseName) {
        Unmarshaller unMarshaller = null;
        try {
            JAXBContext jc = JAXBContext.newInstance(ObjectFactory.class.getPackage().getName());
            unMarshaller = jc.createUnmarshaller();

            URL xsdURL = VestigeWar.class.getResource("vwar-1.0.0.xsd");
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
                ResolveMavenArtifactRequest request = build.resolve(mavenResolver.getGroupId(), mavenResolver.getArtifactId(), mavenResolver.getVersion());
                request.setExtension("war");

                ResolvedMavenArtifact resolvedMavenArtifact;
                try {
                    resolvedMavenArtifact = request.execute(DummyJobHelper.INSTANCE);
                } catch (ResolverException e) {
                    throw new RuntimeException("Unable to fetch war", e);
                }
                ResolvedClassLoaderConfiguration classLoaderConfiguration;
                try {
                    CreateClassLoaderConfigurationRequest createClassLoaderConfigurationRequest = resolvedMavenArtifact.createClassLoaderConfiguration("webapp-" + baseName,
                            ResolveMode.FIXED_DEPENDENCIES, Scope.PLATFORM);
                    createClassLoaderConfigurationRequest.setSelfExcluded(true);
                    classLoaderConfiguration = createClassLoaderConfigurationRequest.execute();
                } catch (ResolverException e) {
                    throw new RuntimeException("Unable to fetch war", e);
                }

                return new VestigeWar(resolvedMavenArtifact.getVestigeJar(), classLoaderConfiguration);
            } finally {
                inputStream.close();
            }
        } catch (JAXBException e) {
            throw new RuntimeException("Unable to read vwar file", e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read vwar file", e);
        }
    }

    private VestigeJar vestigeWar;

    private List<? extends VestigeJar> vestigeWarDependencies;

    public VestigeWar(final VestigeJar vestigeWar, final ResolvedClassLoaderConfiguration classLoaderConfiguration) {
        this.vestigeWar = vestigeWar;
        vestigeWarDependencies = Collections.list(classLoaderConfiguration.getVestigeJarEnumeration());
    }

    public VestigeJar getVestigeWar() {
        return vestigeWar;
    }

    public List<? extends VestigeJar> getVestigeWarDependencies() {
        return vestigeWarDependencies;
    }

}
