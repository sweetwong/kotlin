/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.dependencies.maven.impl

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.Settings
import org.apache.maven.wagon.Wagon
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.classworlds.ClassWorld
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import org.eclipse.aether.collection.DependencyCollectionException
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyFilter
import org.eclipse.aether.internal.transport.wagon.PlexusWagonConfigurator
import org.eclipse.aether.internal.transport.wagon.PlexusWagonProvider
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.*
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.wagon.WagonConfigurator
import org.eclipse.aether.transport.wagon.WagonProvider
import org.eclipse.aether.transport.wagon.WagonTransporterFactory
import org.eclipse.aether.util.filter.DependencyFilterUtils
import org.eclipse.aether.util.graph.visitor.FilteringDependencyVisitor
import org.eclipse.aether.util.graph.visitor.TreeDependencyVisitor
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.DefaultMirrorSelector
import org.eclipse.aether.util.repository.DefaultProxySelector
import java.io.File
import java.util.*

val mavenCentral: RemoteRepository = RemoteRepository.Builder("maven central", "default", "https://repo.maven.apache.org/maven2/").build()

internal class AetherResolveSession(
    localRepoDirectory: File? = null,
    remoteRepos: List<RemoteRepository> = listOf(mavenCentral)
) {

    private val localRepoPath by lazy {
        localRepoDirectory?.absolutePath ?: settings.localRepository
    }

    private val remotes by lazy {
        val proxySelector = settings.activeProxy?.let { proxy ->
            val selector = DefaultProxySelector()
            val auth = with(AuthenticationBuilder()) {
                addUsername(proxy.username)
                addPassword(proxy.password)
                build()
            }
            selector.add(
                Proxy(
                    proxy.protocol,
                    proxy.host,
                    proxy.port,
                    auth
                ), proxy.nonProxyHosts
            )
            selector
        }
        val mirrorSelector = getMirrorSelector()
        remoteRepos.mapNotNull {
            val builder = RemoteRepository.Builder(it)
            if (proxySelector != null) {
                builder.setProxy(proxySelector.getProxy(builder.build()))
            }
            val built = builder.build()
            if (!built.protocol.matches(Regex("https?|file"))) {
                //Logger.warn(
                //        this,
                //        "%s ignored (only S3, HTTP/S, and FILE are supported)",
                //        repo
                //);
                null
            } else {
                mirrorSelector.getMirror(built) ?: built
            }
        }
    }

    private val repositorySystem: RepositorySystem by lazy {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(
            RepositoryConnectorFactory::class.java,
            BasicRepositoryConnectorFactory::class.java
        )
        locator.addService(
            TransporterFactory::class.java,
            FileTransporterFactory::class.java
        )
        locator.addService(
            TransporterFactory::class.java,
            WagonTransporterFactory::class.java
        )

        val container = DefaultPlexusContainer(DefaultContainerConfiguration().apply {
            val realmId = "wagon"
            classWorld = ClassWorld(realmId, Wagon::class.java.classLoader)
            realm = classWorld.getRealm(realmId)
        })

        locator.setServices(
            WagonProvider::class.java,
            PlexusWagonProvider(container)
        )
        locator.setServices(
            WagonConfigurator::class.java,
            PlexusWagonConfigurator(container)
        )

        locator.getService(RepositorySystem::class.java)
    }

    private val repositorySystemSession: RepositorySystemSession by lazy {
        val localRepo = LocalRepository(localRepoPath)
        MavenRepositorySystemUtils.newSession().also {
            it.localRepositoryManager = repositorySystem.newLocalRepositoryManager(it, localRepo)
        }
    }

    fun resolve(
        root: Artifact,
        scope: String,
        transitive: Boolean,
        filter: DependencyFilter?,
        classifier: String? = null,
        extension: String? = null,
    ): List<Artifact> {
        return if (transitive) resolveDependencies(root, scope, filter, classifier, extension)
        else resolveArtifact(root)
    }

    private fun resolveDependencies(
        root: Artifact,
        scope: String,
        filter: DependencyFilter?,
        classifier: String?,
        extension: String?,
    ): List<Artifact> {
        return fetch(
            request(Dependency(root, scope)),
            { req ->
                val requestsBuilder = ArtifactRequestBuilder(classifier, extension)
                val collectionResult = repositorySystem.collectDependencies(repositorySystemSession, req)
                collectionResult.root.accept(
                    TreeDependencyVisitor(
                        FilteringDependencyVisitor(
                            requestsBuilder,
                            filter ?: DependencyFilterUtils.classpathFilter(scope)
                        )
                    )
                )

                val requests = requestsBuilder.requests
                repositorySystem.resolveArtifacts(repositorySystemSession, requests)
            },
            { req, ex ->
                DependencyCollectionException(
                    CollectResult(req),
                    ex.message,
                    ex
                )
            }
        )
    }

    private fun resolveArtifact(artifact: Artifact): List<Artifact> {
        val request = ArtifactRequest()
        request.artifact = artifact
        for (repo in remotes) {
            request.addRepository(repo)
        }

        return fetch(
            request,
            { req -> listOf(repositorySystem.resolveArtifact(repositorySystemSession, req)) },
            { req, ex -> ArtifactResolutionException(listOf(ArtifactResult(req)), ex.message, IllegalArgumentException(ex)) }
        )
    }

    private fun request(root: Dependency): CollectRequest {
        val request = CollectRequest()
        request.root = root
        for (repo in remotes) {
            request.addRepository(repo)
        }
        return request
    }

    private fun <RequestT> fetch(
        request: RequestT,
        fetchBody: (RequestT) -> Collection<ArtifactResult>,
        wrapException: (RequestT, Exception) -> Exception
    ): List<Artifact> {
        val deps: MutableList<Artifact> = LinkedList()
        try {
            var results: Collection<ArtifactResult>
            synchronized(this) {
                results = fetchBody(request)
            }
            for (res in results) {
                deps.add(res.artifact)
            }
            // @checkstyle IllegalCatch (1 line)
        } catch (ex: Exception) {
            throw wrapException(request, ex)
        }
        return deps
    }

    private fun getMirrorSelector(): DefaultMirrorSelector {
        val selector = DefaultMirrorSelector()
        val mirrors = settings.mirrors
        if (mirrors != null) {
            for (mirror in mirrors) {
                selector.add(
                    mirror.id, mirror.url, mirror.layout, false, false,
                    mirror.mirrorOf, mirror.mirrorOfLayouts
                )
            }
        }
        return selector
    }

    private val settings: Settings by lazy {
        createMavenSettings()
    }
}