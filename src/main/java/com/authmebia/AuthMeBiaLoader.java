package com.authmebia;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;

@SuppressWarnings("UnstableApiUsage")
public class AuthMeBiaLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(
                new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build()
        );

        resolver.addDependency(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("com.squareup.okhttp3:okhttp:4.12.0"), null));
        resolver.addDependency(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("com.squareup.okio:okio:3.9.0"), null));
        resolver.addDependency(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.24"), null));
        resolver.addDependency(new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact("com.google.code.gson:gson:2.10.1"), null));

        classpathBuilder.addLibrary(resolver);
    }
}
