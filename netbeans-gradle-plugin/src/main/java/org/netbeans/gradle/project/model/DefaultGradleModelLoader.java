package org.netbeans.gradle.project.model;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.LongRunningOperation;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.util.GradleVersion;
import org.jtrim2.cancel.CancellationToken;
import org.jtrim2.concurrent.Tasks;
import org.jtrim2.executor.MonitorableTaskExecutorService;
import org.jtrim2.executor.TaskExecutor;
import org.jtrim2.property.PropertySource;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.model.BuildOperationArgs;
import org.netbeans.gradle.model.OperationInitializer;
import org.netbeans.gradle.project.LoadedProjectManager;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.NbGradleProjectFactory;
import org.netbeans.gradle.project.NbStrings;
import org.netbeans.gradle.project.api.config.PropertyReference;
import org.netbeans.gradle.project.api.modelquery.GradleTarget;
import org.netbeans.gradle.project.api.task.CommandCompleteListener;
import org.netbeans.gradle.project.api.task.DaemonTaskContext;
import org.netbeans.gradle.project.extensions.NbGradleExtensionRef;
import org.netbeans.gradle.project.model.issue.ModelLoadIssue;
import org.netbeans.gradle.project.model.issue.ModelLoadIssueReporter;
import org.netbeans.gradle.project.model.issue.ModelLoadIssues;
import org.netbeans.gradle.project.properties.global.CommonGlobalSettings;
import org.netbeans.gradle.project.script.ScriptFileProvider;
import org.netbeans.gradle.project.tasks.GradleArguments;
import org.netbeans.gradle.project.tasks.GradleDaemonFailures;
import org.netbeans.gradle.project.tasks.GradleDaemonManager;
import org.netbeans.gradle.project.tasks.GradleTasks;
import org.netbeans.gradle.project.util.GradleVersions;
import org.netbeans.gradle.project.util.NbTaskExecutors;
import org.netbeans.gradle.project.view.GlobalErrorReporter;

public final class DefaultGradleModelLoader implements ModelLoader<NbGradleModel> {
    private static final Logger LOGGER = Logger.getLogger(DefaultGradleModelLoader.class.getName());

    private static final TaskExecutor DEFAULT_PROJECT_LOADER
            = NbTaskExecutors.newExecutor("Gradle-Project-Loader", 1);

    private static final MonitorableTaskExecutorService DEFAULT_MODEL_LOAD_NOTIFIER
            = NbTaskExecutors.newExecutor("Gradle-Project-Load-Notifier", 1);

    private static final MonitorableTaskExecutorService DEFAULT_MODEL_PERSISTER
            = NbTaskExecutors.newExecutor("Gradle-Project-Model-Persister", 1);

    private static final AtomicReference<GradleModelCache> DEFAULT_CACHE_REF
            = new AtomicReference<>(null);

    private final NbGradleProject project;
    private final TaskExecutor projectLoader;
    private final MonitorableTaskExecutorService modelLoadNotifier;
    private final LoadedProjectManager loadedProjectManager;
    private final PersistentModelCache<NbGradleModel> persistentCache;
    private final Supplier<? extends GradleModelCache> cacheRef;
    private final CacheSizeIncreaser cacheSizeIncreaser;

    private final AtomicBoolean modelWasSetOnce;

    private DefaultGradleModelLoader(Builder builder) {
        this.project = builder.project;
        this.projectLoader = builder.projectLoader;
        this.modelLoadNotifier = builder.modelLoadNotifier;
        this.loadedProjectManager = builder.loadedProjectManager;
        this.persistentCache = builder.persistentCache;
        this.cacheRef = builder.cacheRef;
        this.cacheSizeIncreaser = builder.cacheSizeIncreaser;
        this.modelWasSetOnce = new AtomicBoolean(false);
    }

    private static void updateProjectFromCacheIfNeeded(NbGradleModel newModel) {
        File projectDir = newModel.getProjectDir();
        NbGradleProject project = LoadedProjectManager.getDefault().tryGetLoadedProject(projectDir);
        if (project != null) {
            project.tryReplaceModel(newModel);
        }
    }

    private static GradleModelCache getDefaultCache() {
        GradleModelCache result = DEFAULT_CACHE_REF.get();
        if (result == null) {
            final PropertySource<Integer> cacheSize = CommonGlobalSettings.getDefault().projectCacheSize().getActiveSource();
            result = new GradleModelCache(cacheSize.getValue());
            if (DEFAULT_CACHE_REF.compareAndSet(null, result)) {
                final GradleModelCache cache = result;
                cacheSize.addChangeListener(() -> {
                    cache.setMaxCapacity(cacheSize.getValue());
                });
                cache.setMaxCapacity(cacheSize.getValue());
                cache.addModelUpdateListener(DefaultGradleModelLoader::updateProjectFromCacheIfNeeded);
            }
            else {
                result = DEFAULT_CACHE_REF.get();
            }
        }
        return result;
    }

    private GradleModelCache getCache() {
        return cacheRef.get();
    }

    public static GradleConnector createGradleConnector(CancellationToken cancelToken, Project project) {
        return createGradleConnectorRef(cancelToken, project).getGradleConnector();
    }

    public static GradleConnectorRef createGradleConnectorRef(CancellationToken cancelToken, Project project) {
        return GradleConnectorRef.open(cancelToken, project);
    }

    public static Path getAppliedRootProjectDir(NbGradleProject project) {
        return getProjectLoadKey(project).getAppliedRootProjectDir();
    }

    private static ProjectLoadRequest getProjectLoadKey(NbGradleProject project) {
        SettingsGradleDef settingsFile = project.getPreferredSettingsGradleDef();
        return new ProjectLoadRequest(project, settingsFile);
    }

    private NbGradleModel tryGetFromCache(ProjectLoadRequest loadRequest) {
        File settingsFile = loadRequest.findAppliedSettingsFileAsFile();
        return getCache().tryGet(loadRequest.project.getProjectDirectoryAsFile(), settingsFile);
    }

    public static List<NbGradleExtensionRef> getUnloadedExtensions(
            NbGradleProject project,
            NbGradleModel baseModels) {

        List<NbGradleExtensionRef> result = new ArrayList<>();
        for (NbGradleExtensionRef extension: project.getExtensions().getExtensionRefs()) {
            if (!baseModels.hasModelOfExtension(extension)) {
                result.add(extension);
            }
        }
        return result;
    }

    private boolean hasUnloadedExtension(NbGradleModel cached) {
        for (NbGradleExtensionRef extension: project.getExtensions().getExtensionRefs()) {
            if (!cached.hasModelOfExtension(extension)) {
                return true;
            }
        }
        return false;
    }

    private void onModelLoaded(
            final NbGradleModel model,
            final Throwable error,
            final ModelRetrievedListener<? super NbGradleModel> listener) {

        if (model == null && error == null) {
            return;
        }

        modelWasSetOnce.set(true);

        if (modelLoadNotifier.isExecutingInThis()) {
            listener.updateModel(model, error);
        }
        else {
            modelLoadNotifier.execute(() -> listener.updateModel(model, error));
        }
    }

    private static void reportModelLoadError(NbGradleProject project, GradleModelLoadError error) {
        Throwable unexpectedError = error.getUnexpectedError();
        if (unexpectedError != null) {
            ModelLoadIssue unexpectedIssue = ModelLoadIssues
                    .projectModelLoadError(project, null, null, unexpectedError);
            ModelLoadIssueReporter.reportAllIssues(Collections.singleton(unexpectedIssue));
        }

        Throwable buildScriptEvaluationError = error.getBuildScriptEvaluationError();
        if (buildScriptEvaluationError != null) {
            ModelLoadIssueReporter.reportBuildScriptError(project, buildScriptEvaluationError);
        }
    }

    private NbGradleModel tryGetFromPersistentCache(ProjectLoadRequest projectLoadKey) {
        if (modelWasSetOnce.get()) {
            return null;
        }

        try {
            return persistentCache.tryGetModel(projectLoadKey.getPersistentModelKey());
        } catch (IOException ex) {
            LOGGER.log(Level.INFO,
                    "Failed to read persistent cache for project " + projectLoadKey.project.getProjectDirectoryAsFile(),
                    ex);
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE,
                    "Unexpected error while trying to read the persistent cache for project " + projectLoadKey.project.getProjectDirectoryAsFile(),
                    ex);
        }
        return null;
    }

    @Override
    public void fetchModel(
            final boolean mayFetchFromCache,
            final ModelRetrievedListener<? super NbGradleModel> listener,
            final Runnable aboutToCompleteListener) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(aboutToCompleteListener, "aboutToCompleteListener");

        if (modelWasSetOnce.get()) {
            fetchModelWithoutPersistentCache(mayFetchFromCache, listener, aboutToCompleteListener);
            return;
        }

        modelLoadNotifier.execute(() -> {
            NbGradleModel model = null;
            boolean needLoadFromScripts = true;

            try {
                ProjectLoadRequest projectLoadKey = getProjectLoadKey(project);
                model = mayFetchFromCache ? tryGetFromCache(projectLoadKey) : null;
                if (model == null || hasUnloadedExtension(model)) {
                    model = tryGetFromPersistentCache(projectLoadKey);
                }
                else {
                    needLoadFromScripts = false;
                }
            } finally {
                onModelLoaded(model, null, listener);
                if (needLoadFromScripts) {
                    fetchModelWithoutPersistentCache(mayFetchFromCache, listener, aboutToCompleteListener);
                }
            }
        });
    }

    private static boolean isInProjectTree(NbGradleProject project, NbGradleModel rootModel) {
        NbGradleProjectTree projectTree = rootModel.getGenericInfo().getProjectDef().getRootProject();
        return isInProjectTree(project.getProjectDirectoryAsFile(), projectTree);
    }

    private static boolean isInProjectTree(File projectDir, NbGradleProjectTree projectTree) {
        if (Objects.equals(projectDir, projectTree.getProjectDir())) {
            return true;
        }

        for (NbGradleProjectTree child: projectTree.getChildren()) {
            if (isInProjectTree(projectDir, child)) {
                return true;
            }
        }
        return false;
    }

    private ProjectLoadRequest fixProjectLoadKey(
            CancellationToken cancelToken,
            ProjectLoadRequest projectLoadKey,
            ProgressHandle progress) throws IOException, GradleModelLoadError {
        if (!CommonGlobalSettings.getDefault().loadRootProjectFirst().getActiveValue()) {
            return projectLoadKey;
        }

        Path rootProjectDir = projectLoadKey.getAppliedRootProjectDir();
        if (Objects.equals(rootProjectDir, projectLoadKey.project.getProjectDirectoryAsPath())) {
            LOGGER.log(Level.INFO, "Project is expected to be the root project, skipping project load key fix for {0}.", rootProjectDir);
            return projectLoadKey;
        }

        NbGradleProject rootProject = NbGradleProjectFactory.tryLoadSafeGradleProject(rootProjectDir);
        if (rootProject != null) {
            return fixProjectLoadKeyWithRootProject(cancelToken, projectLoadKey, rootProject, progress);
        }

        LOGGER.log(Level.INFO, "Failed to load root project for {0} attempting to guess another root project.", project.getProjectDirectoryAsPath());

        return fixProjectLoadKeyWithGuessed(
                cancelToken,
                new ProjectLoadRequest(project, SettingsGradleDef.DEFAULT),
                progress);
    }

    private ProjectLoadRequest fixProjectLoadKeyWithGuessed(
            CancellationToken cancelToken,
            ProjectLoadRequest projectLoadKey,
            ProgressHandle progress) throws IOException, GradleModelLoadError {

        Path rootProjectDir = projectLoadKey.getAppliedRootProjectDir();
        NbGradleProject rootProject = NbGradleProjectFactory.tryLoadSafeGradleProject(rootProjectDir);
        if (rootProject != null) {
            LOGGER.log(Level.INFO, "Found another root project for {0}: {1}.", new Object[]{
                project.getProjectDirectoryAsPath(),
                rootProjectDir});
            return fixProjectLoadKeyWithRootProject(cancelToken, projectLoadKey, rootProject, progress);
        }

        LOGGER.log(Level.INFO, "Could not find another root project for {0} using whatever Gradle chooses.",
                project.getProjectDirectoryAsPath());

        return projectLoadKey;
    }

    private ProjectLoadRequest fixProjectLoadKeyWithRootProject(
            CancellationToken cancelToken,
            ProjectLoadRequest projectLoadKey,
            NbGradleProject rootProject,
            ProgressHandle progress) throws IOException, GradleModelLoadError {

        ProjectLoadRequest rootLoadKey = new ProjectLoadRequest(rootProject, projectLoadKey.settingsGradleDef);
        NbGradleModel rootModel = tryGetFromCache(rootLoadKey);
        if (rootModel == null || !isUpToDateModel(rootModel, project.getProjectDirectoryAsPath())) {
            if (rootModel != null) {
                LOGGER.log(Level.INFO,
                        "Reloading the guessed root project of {0} because its project directory was created after parsing the root project.",
                        project.getProjectDirectoryAsPath());
            }
            rootModel = loadModelWithProgress(cancelToken, rootLoadKey, progress, null);
            assert rootModel != null;
        }

        if (!isInProjectTree(project, rootModel)) {
            LOGGER.log(Level.INFO, "Project ({0}) is not found in the project tree of {1}. Assuming the project to be a single separate project.",
                    new Object[]{project.getProjectDirectoryAsFile(), rootModel.getProjectDir()});
            return new ProjectLoadRequest(project, SettingsGradleDef.NO_SETTINGS);
        }

        return projectLoadKey;
    }

    private static boolean isUpToDateModel(NbGradleModel rootModel, Path dir) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
            FileTime creationTime = attrs.creationTime();
            if (creationTime == null) {
                return true;
            }

            return rootModel.getGenericInfo().getCreateTimeEpochMs() >= creationTime.toMillis();
        } catch (IOException ex) {
            return true;
        }
    }

    private CommandCompleteListener projectTaskCompleteListener(final Runnable loadCompletedListener) {
        return (Throwable error) -> {
            try {
                GradleTasks.projectTaskCompleteListener(project).onComplete(error);
            } finally {
                loadCompletedListener.run();
            }
        };
    }

    private void fetchModelWithoutPersistentCache(
            final boolean mayFetchFromCache,
            final ModelRetrievedListener<? super NbGradleModel> listener,
            Runnable aboutToCompleteListener) {

        final Runnable safeCompleteListener = Tasks.runOnceTask(aboutToCompleteListener);

        String caption = NbStrings.getLoadingProjectText(project.getDisplayName());
        GradleDaemonManager.submitGradleTask(projectLoader, caption, (CancellationToken cancelToken, ProgressHandle progress) -> {
            ProjectLoadRequest projectLoadKey = getProjectLoadKey(project);

            NbGradleModel model = null;
            Throwable error = null;
            try {
                ProjectLoadRequest fixedLoadKey = fixProjectLoadKey(cancelToken, projectLoadKey, progress);
                if (mayFetchFromCache) {
                    model = tryGetFromCache(fixedLoadKey);
                }
                if (model == null || hasUnloadedExtension(model)) {
                    model = loadModelWithProgress(cancelToken, fixedLoadKey, progress, model);
                }
            } catch (IOException | BuildException ex) {
                error = ex;
            } catch (GradleConnectionException ex) {
                error = ex;
            } catch (GradleModelLoadError ex) {
                error = ex;
                reportModelLoadError(project, ex);
            } finally {
                safeCompleteListener.run();
                onModelLoaded(model, error, listener);

                if (error != null) {
                    GradleDaemonFailures.getDefaultHandler().tryHandleFailure(error);
                }
            }
        }, true, projectTaskCompleteListener(safeCompleteListener));
    }

    private void saveToPersistentCache(Collection<NbGradleModel> models) {
        try {
            persistentCache.saveGradleModels(models);
        } catch (IOException ex) {
            LOGGER.log(Level.INFO, "Failed to save into the persistent cache.", ex);
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "Unexpected error while saving to the persistent cache.", ex);
        }
    }

    private NbGradleModel introduceLoadedModel(NbGradleModel model, boolean replaced) {
        NbGradleModel modelToSave;
        if (replaced) {
            modelToSave = model;
            getCache().replaceEntry(model);
        }
        else {
            modelToSave = getCache().updateEntry(model);
        }

        NbGradleProject ownerProject = loadedProjectManager.tryGetLoadedProject(model.getProjectDir());
        if (ownerProject != null) {
            ownerProject.tryReplaceModel(modelToSave);
        }

        return modelToSave;
    }

    private void introduceProjects(
            List<NbGradleModel> otherModels,
            NbGradleModel mainModel) {

        int numberOfModels = otherModels.size() + 1;
        // Required one more than actually needed to create room for a buildSrc project.
        cacheSizeIncreaser.requiresCacheSize(getCache(), numberOfModels + 1);

        List<NbGradleModel> toSave = new ArrayList<>(numberOfModels);
        for (NbGradleModel model: otherModels) {
            toSave.add(introduceLoadedModel(model, false));
        }
        toSave.add(introduceLoadedModel(mainModel, true));
        saveToPersistentCache(toSave);
    }

    public static void setupLongRunningOP(OperationInitializer setup, LongRunningOperation op) {
        BuildOperationArgs args = new BuildOperationArgs();
        setup.initOperation(args);
        args.setupLongRunningOP(op);
    }

    private static DaemonTaskContext daemonTaskContext(Project project) {
        return new DaemonTaskContext(project, true);
    }

    private static List<String> getModelEvaluateJvmArguments(Project project) {
        return GradleArguments.getExtraJvmArgs(daemonTaskContext(project));
    }

    private static DefaultModelBuilderSetup modelBuilderSetup(ProjectLoadRequest projectLoadKey, ProgressHandle progress) {
        return new DefaultModelBuilderSetup(
                projectLoadKey.project,
                getModelEvaluateArguments(projectLoadKey),
                getModelEvaluateJvmArguments(projectLoadKey.project),
                progress);
    }

    public static DefaultModelBuilderSetup modelBuilderSetup(NbGradleProject project, ProgressHandle progress) {
        return modelBuilderSetup(getProjectLoadKey(project), progress);
    }

    public static DefaultModelBuilderSetup modelBuilderSetup(Project project, ProgressHandle progress) {
        NbGradleProject gradleProject = NbGradleProjectFactory.tryGetGradleProject(project);
        if (gradleProject != null) {
            return modelBuilderSetup(gradleProject, progress);
        }

        // This path should not be taken under normal circumstances.
        // That is, this path is only taken if for some weird reasons a non-gradle
        // project is being interpreted as a Gradle project.
        return new DefaultModelBuilderSetup(
                project,
                getModelEvaluateArguments(project, SettingsGradleDef.DEFAULT),
                getModelEvaluateJvmArguments(project),
                progress);
    }

    private NbGradleModel loadModelWithProgress(
            CancellationToken cancelToken,
            final ProjectLoadRequest projectLoadKey,
            final ProgressHandle progress,
            final NbGradleModel cachedEntry) throws IOException, GradleModelLoadError {

        File projectDir = project.getProjectDirectoryAsFile();

        LOGGER.log(Level.INFO,
                "Loading Gradle project from directory: {0}, settings.gradle: {1}",
                new Object[]{projectDir, projectLoadKey.settingsGradleDef});

        GradleConnector gradleConnector = createGradleConnector(cancelToken, project);
        gradleConnector.forProjectDirectory(projectDir);
        ProjectConnection projectConnection = null;

        NbModelLoader.Result loadedModels;
        try {
            projectConnection = gradleConnector.connect();

            DefaultModelBuilderSetup setup = modelBuilderSetup(projectLoadKey, progress);

            ModelBuilder<BuildEnvironment> modelBuilder = projectConnection.model(BuildEnvironment.class);
            setupLongRunningOP(setup, modelBuilder);

            BuildEnvironment env = modelBuilder.get();
            reportKnownIssues(env);

            GradleTarget gradleTarget = new GradleTarget(
                    setup.getJDKVersion(),
                    GradleVersion.version(env.getGradle().getGradleVersion()));
            NbModelLoader modelLoader = chooseModel(projectLoadKey.settingsGradleDef, gradleTarget, setup);

            loadedModels = modelLoader.loadModels(project, projectConnection, progress);
        } finally {
            if (projectConnection != null) {
                projectConnection.close();
            }
        }

        ModelLoadIssueReporter.reportAllIssues(loadedModels.getIssues());

        NbGradleModel result = cachedEntry != null
                ? cachedEntry.updateEntry(loadedModels.getMainModel())
                : loadedModels.getMainModel();

        introduceProjects(loadedModels.getOtherModels(), result);

        return result;
    }

    private static void reportKnownIssues(BuildEnvironment env) {
        GradleVersion version = GradleVersion.version(env.getGradle().getGradleVersion());
        if (GradleVersions.VERSION_1_7.compareTo(version) < 0
                && GradleVersions.VERSION_1_8.compareTo(version) >= 0) {

            String gradleVersion = env.getGradle().getGradleVersion();
            GlobalErrorReporter.showIssue(NbStrings.getIssueWithGradle18Message(gradleVersion));
        }
        else if (GradleVersions.VERSION_2_3.equals(version.getBaseVersion())) {
            String gradleVersion = env.getGradle().getGradleVersion();
            GlobalErrorReporter.showIssue(NbStrings.getIssueWithGradle23Message(gradleVersion));
        }
    }

    private static NbModelLoader chooseModel(
            SettingsGradleDef settingsGradleDef,
            GradleTarget gradleTarget,
            OperationInitializer setup) {

        NbModelLoader result = new NbGradle18ModelLoader(settingsGradleDef, setup, gradleTarget);

        LOGGER.log(Level.INFO, "Using model loader: {0}", result.getClass().getSimpleName());
        return result;
    }

    public static NbGradleModel createEmptyModel(Path projectDir, ScriptFileProvider scriptProvider) {
        return new NbGradleModel(
                NbGradleMultiProjectDef.createEmpty(projectDir, scriptProvider),
                ModelLoadUtils.findSettingsGradle(projectDir, scriptProvider));
    }

    private static List<String> getModelEvaluateArguments(Project project, SettingsGradleDef settingsDef) {
        return GradleArguments.getExtraArgs(settingsDef, daemonTaskContext(project));
    }

    private static List<String> getModelEvaluateArguments(ProjectLoadRequest projectLoadKey) {
        return GradleArguments.getExtraArgs(
                projectLoadKey.settingsGradleDef,
                daemonTaskContext(projectLoadKey.project));
    }

    public static void ensureCacheSize(int minimumCacheSize) {
        ensureCacheSize(getDefaultCache(), minimumCacheSize);
    }

    private static void ensureCacheSize(GradleModelCache cache, int minimumCacheSize) {
        if (cache.getMaxCapacity() >= minimumCacheSize) {
            return;
        }

        PropertyReference<Integer> projectCacheSize = CommonGlobalSettings.getDefault().projectCacheSize();
        Integer prevCacheSize = projectCacheSize.getActiveValue();
        if (prevCacheSize >= minimumCacheSize) {
            return;
        }
        projectCacheSize.setValue(minimumCacheSize);
        cache.setMaxCapacityToAtLeast(minimumCacheSize);

        GlobalErrorReporter.showWarning(NbStrings.getTooSmallCache(prevCacheSize, minimumCacheSize));
    }

    public static final class Builder {
        private static final PersistentProjectModelStoreFactory DEFAULT_MODEL_STORE_FACTORY
                = new PersistentProjectModelStoreFactory();
        private static final LazyPersistentModelStoreFactory<NbGradleModel> DEFAULT_LAZY_MODEL_STORE_FACTORY
                = new LazyPersistentModelStoreFactory<>(DEFAULT_MODEL_STORE_FACTORY.getModelPersister(), DEFAULT_MODEL_PERSISTER);

        private final NbGradleProject project;

        private TaskExecutor projectLoader;
        private MonitorableTaskExecutorService modelLoadNotifier;
        private LoadedProjectManager loadedProjectManager;
        private PersistentModelCache<NbGradleModel> persistentCache;
        private Supplier<? extends GradleModelCache> cacheRef;
        private CacheSizeIncreaser cacheSizeIncreaser;

        public Builder(NbGradleProject project) {
            this.project = Objects.requireNonNull(project, "project");
            this.projectLoader = DEFAULT_PROJECT_LOADER;
            this.modelLoadNotifier = DEFAULT_MODEL_LOAD_NOTIFIER;
            this.loadedProjectManager = LoadedProjectManager.getDefault();
            this.persistentCache = new MultiFileModelCache<>(defaultModelPersister(project), (NbGradleModel model) -> {
                try {
                    return new PersistentModelKey(model).normalize();
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            this.cacheRef = DefaultGradleModelLoader::getDefaultCache;
            this.cacheSizeIncreaser = DefaultGradleModelLoader::ensureCacheSize;
        }

        private static PersistentModelStore<NbGradleModel> defaultModelPersister(NbGradleProject project) {
            return DEFAULT_LAZY_MODEL_STORE_FACTORY.createStore(DEFAULT_MODEL_STORE_FACTORY.createModelStore(project));
        }

        public void setProjectLoader(TaskExecutor projectLoader) {
            this.projectLoader = Objects.requireNonNull(projectLoader, "projectLoader");
        }

        public void setModelLoadNotifier(MonitorableTaskExecutorService modelLoadNotifier) {
            this.modelLoadNotifier = Objects.requireNonNull(modelLoadNotifier, "modelLoadNotifier");
        }

        public void setLoadedProjectManager(LoadedProjectManager loadedProjectManager) {
            this.loadedProjectManager = Objects.requireNonNull(loadedProjectManager, "loadedProjectManager");
        }

        public void setPersistentCache(PersistentModelCache<NbGradleModel> persistentCache) {
            this.persistentCache = Objects.requireNonNull(persistentCache, "persistentCache");
        }

        public void setCacheRef(final GradleModelCache cache) {
            Objects.requireNonNull(cache, "cache");
            this.cacheRef = () -> cache;
        }

        public DefaultGradleModelLoader create() {
            return new DefaultGradleModelLoader(this);
        }
    }

    private static final class ProjectLoadRequest {
        public final NbGradleProject project;
        public final SettingsGradleDef settingsGradleDef;

        public ProjectLoadRequest(NbGradleProject project, SettingsGradleDef settingsGradleDef) {
            assert project != null;
            assert settingsGradleDef != null;

            this.project = project;
            this.settingsGradleDef = settingsGradleDef;
        }

        public PersistentModelKey getPersistentModelKey() throws IOException {
            return new PersistentModelKey(getAppliedRootProjectDir(), project.getProjectDirectoryAsPath()).normalize();
        }

        public File findAppliedSettingsFileAsFile() {
            Path result = findAppliedSettingsFile();
            return result != null ? result.toFile() : null;
        }

        public Path findAppliedSettingsFile() {
            Path settingsFile = settingsGradleDef.getSettingsGradle();

            if (settingsFile != null) {
                return settingsFile;
            }

            return ModelLoadUtils.findSettingsGradle(
                    project.getProjectDirectoryAsPath(),
                    project.getScriptFileProvider());
        }

        public Path getAppliedRootProjectDir() {
            Path appliedSettingsFile = settingsGradleDef.getSettingsGradle();
            if (appliedSettingsFile == null) {
                appliedSettingsFile = findAppliedSettingsFile();
            }

            if (appliedSettingsFile != null) {
                Path settingsFileDir = appliedSettingsFile.getParent();
                if (settingsFileDir != null) {
                    return settingsFileDir;
                }
            }

            return project.getProjectDirectoryAsPath();
        }
    }
}
