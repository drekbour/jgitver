/**
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.brouillard.oss.jgitver.impl;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import fr.brouillard.oss.jgitver.BranchingPolicy;
import fr.brouillard.oss.jgitver.BranchingPolicy.BranchNameTransformations;
import fr.brouillard.oss.jgitver.GitVersionCalculator;
import fr.brouillard.oss.jgitver.LookupPolicy;
import fr.brouillard.oss.jgitver.ScriptType;
import fr.brouillard.oss.jgitver.Strategies;
import fr.brouillard.oss.jgitver.Version;
import fr.brouillard.oss.jgitver.impl.metadata.MetadataHolder;
import fr.brouillard.oss.jgitver.metadata.Metadatas;
import fr.brouillard.oss.jgitver.metadata.TagType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import static fr.brouillard.oss.jgitver.impl.GitUtils.tagsOf;

public class GitVersionCalculatorImpl implements GitVersionCalculator {
    private MetadataHolder metadatas;
    private Repository repository;
    private boolean mavenLike = false;
    private boolean autoIncrementPatch = false;
    private boolean useDistance = true;
    private boolean useGitCommitId = false;
    private boolean useGitCommitTimestamp = false;
    private boolean useDirty = false;
    private boolean useLongFormat = false;
    private boolean useSnapshot = false;
    private boolean forceComputation = false;
    private int gitCommitIdLength = 8;
    private int maxDepth = Integer.MAX_VALUE;
    private List<BranchingPolicy> qualifierBranchingPolicies;
    private boolean useDefaultBranchingPolicy = true;
    private Strategies versionStrategy = null;
    private String tagVersionPattern = null;
    private String versionPattern = null;
    private LookupPolicy lookupPolicy = LookupPolicy.MAX;
    private ScriptType scriptType = ScriptType.GROOVY;
    private String script = "";

    private final File gitRepositoryLocation;

    private final Function<Ref, ObjectId> refToObjectIdFunction = r -> (r.getPeeledObjectId() != null)
            ? r.getPeeledObjectId() : r.getObjectId();

    private boolean computationRequired = true;
    private Version computedVersion;
    private String computedHeadSHA1;

    private final SimpleDateFormat dtfmt;
    private Pattern findTagVersionPattern = VersionNamingConfiguration.DEFAULT_FIND_TAG_VERSION_PATTERN;

    GitVersionCalculatorImpl(File gitRepositoryLocation) throws IOException {
        this.gitRepositoryLocation = gitRepositoryLocation;

        dtfmt = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US);
        setNonQualifierBranches("master");
    }

    private Repository openRepository() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        return builder.findGitDir(gitRepositoryLocation).build();
    }

    /**
     * Computes the Version object and the associated metadatas.
     * Store results in cache for later reuse.
     */
    private void computeVersion() {
        metadatas = new MetadataHolder();

        try {
            this.repository = openRepository();
        } catch (Exception ex) {
            setComputedVersion(Version.NOT_GIT_VERSION);
            return;
        }
        try (Git git = new Git(repository)) {
            VersionStrategy strategy;

            List<BranchingPolicy> policiesToUse = new LinkedList<>(qualifierBranchingPolicies);
            if (useDefaultBranchingPolicy) {
                policiesToUse.add(BranchingPolicy.DEFAULT_FALLBACK);
            }

            VersionNamingConfiguration vnc = new VersionNamingConfiguration(
                    findTagVersionPattern,
                    policiesToUse.toArray(new BranchingPolicy[policiesToUse.size()])
            );

            if (versionStrategy == null) {
                // no versionStrategy defined yet
                // we use historical mavenLike for compatibility purposes
                versionStrategy = mavenLike ? Strategies.MAVEN : Strategies.CONFIGURABLE;
            }

            switch (versionStrategy) {
                case MAVEN:
                    strategy = new MavenVersionStrategy(vnc, repository, git, metadatas)
                            .setForceComputation(forceComputation)
                            .setUseDirty(useDirty);
                    break;
                case CONFIGURABLE:
                    strategy = new ConfigurableVersionStrategy(vnc, repository, git, metadatas)
                            .setAutoIncrementPatch(autoIncrementPatch)
                            .setUseDistance(useDistance)
                            .setUseDirty(useDirty)
                            .setUseGitCommitId(useGitCommitId)
                            .setGitCommitIdLength(gitCommitIdLength)
                            .setUseCommitTimestamp(useGitCommitTimestamp)
                            .setUseLongFormat(useLongFormat)
                            .setUseSnapshot(useSnapshot);
                    break;
                case PATTERN:
                    strategy = new PatternVersionStrategy(vnc, repository, git, metadatas)
                            .setAutoIncrementPatch(autoIncrementPatch)
                            .setVersionPattern(versionPattern)
                            .setTagVersionPattern(tagVersionPattern);
                    break;
                case SCRIPT:
                    strategy = new ScriptVersionStrategy(vnc, repository, git, metadatas)
                        .setScriptType(scriptType)
                        .setScript(script);
                    break;
                default:
                    throw new IllegalStateException("unknown strategy: " + versionStrategy);
            }

            strategy.setSearchDepthLimit(maxDepth);
            Version calculatedVersion = buildVersion(git, strategy);

            setComputedVersion(calculatedVersion);
        }
    }

    @Override
    public Version getVersionObject(boolean forceComputation) {
        if (forceComputation) {
            computeVersion();
        }
        return getVersionObject();
    }

    @Override
    public Version getVersionObject() {
        if (needToRecompute()) {
            computeVersion();
        }
        return this.computedVersion;
    }

    private boolean needToRecompute() {
        if (this.computationRequired || this.repository == null) {
            return true;
        }

        try {
            ObjectId head = repository.resolve("HEAD");
            String actualHeadSHA1 = head != null ? head.getName() : "";
            return  !actualHeadSHA1.equals(computedHeadSHA1);
        } catch (IOException e) {
            throw new IllegalStateException("failure to retrieve actual HEAD SHA1", e);
        }
    }

    private void setComputedVersion(Version computedVersion) {
        this.computedVersion = computedVersion;

        try {
            if (repository != null) {
                ObjectId head = repository.resolve("HEAD");
                this.computedHeadSHA1 = head != null ? head.getName() : "";
            }
        } catch (IOException e) {
            throw new IllegalStateException("failure to retrieve current HEAD SHA1", e);
        }

        this.computationRequired = false;
    }

    private boolean hasPatchVersionBeenIncremented(VersionStrategy strategy, Version calculatedVersion) {
        if (Version.EMPTY_REPOSITORY_VERSION.equals(calculatedVersion)) {
            return false;
        }
        return (autoIncrementPatch || strategy instanceof MavenVersionStrategy)
                && metadatas.meta(Metadatas.HEAD_VERSION_TAGS).get().isEmpty();
    }

    private void provideNextVersionsMetadatas(Version calculatedVersion, boolean patchIsIncremented) {
        Version unqualifiedCalculatedVersion = calculatedVersion.noQualifier();
        Version baseVersion = Version.parse(metadatas.meta(Metadatas.BASE_VERSION).get());
        Version unqualifiedBaseVersion = baseVersion.noQualifier();

        if (baseVersion.isSnapshot() || TagType.LIGHTWEIGHT.name().equals(metadatas.meta(Metadatas.BASE_TAG_TYPE).orElse(null))) {
            // base version was a snapshot or a lightweight tag, meaning the version it represent has never been published yet
            metadatas.registerMetadata(Metadatas.NEXT_PATCH_VERSION, unqualifiedBaseVersion.toString());
            if (unqualifiedBaseVersion.getPatch() == 0) {
                metadatas.registerMetadata(Metadatas.NEXT_MINOR_VERSION, unqualifiedBaseVersion.toString());
            } else {
                metadatas.registerMetadata(Metadatas.NEXT_MINOR_VERSION, unqualifiedBaseVersion.incrementMinor().toString());
            }
            if (unqualifiedBaseVersion.getMinor() == 0) {
                metadatas.registerMetadata(Metadatas.NEXT_MAJOR_VERSION, unqualifiedBaseVersion.toString());
            } else {
                metadatas.registerMetadata(Metadatas.NEXT_MAJOR_VERSION, unqualifiedBaseVersion.incrementMajor().toString());
            }
        } else {
            if (patchIsIncremented) {
                if (unqualifiedCalculatedVersion.equals(unqualifiedBaseVersion)) {
                    // we're probably on the tag itself don't do anything to the base version
                } else {
                    // we need to decrement the patch number
                    unqualifiedCalculatedVersion = new Version(
                            unqualifiedCalculatedVersion.getMajor(),
                            unqualifiedCalculatedVersion.getMinor(),
                            unqualifiedCalculatedVersion.getPatch() - 1
                    );
                }
            }

            metadatas.registerMetadata(Metadatas.NEXT_MAJOR_VERSION, unqualifiedCalculatedVersion.incrementMajor().toString());
            metadatas.registerMetadata(Metadatas.NEXT_MINOR_VERSION, unqualifiedCalculatedVersion.incrementMinor().toString());
            metadatas.registerMetadata(Metadatas.NEXT_PATCH_VERSION, unqualifiedCalculatedVersion.incrementPatch().toString());
        }
    }

    @Override
    public String getVersion() {
        return getVersion(false);
    }

    @Override
    public String getVersion(boolean forceComputation) {
        return getVersionObject(forceComputation).toString();
    }

    private Version buildVersion(Git git, VersionStrategy<?> strategy) {
        try {
            boolean dirty = GitUtils.isDirty(git);
            metadatas.registerMetadata(Metadatas.DIRTY, "" + dirty);
            if (dirty) {
                metadatas.registerMetadata(Metadatas.DIRTY_TEXT, "dirty");
            }

            ObjectId rootId = repository.resolve("HEAD");

            List<Ref> rawTags = git.tagList().call();

            List<Ref> ALL_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> ALL_ANNOTATED_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> ALL_LIGHTWEIGHT_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> ALL_VERSION_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> ALL_VERSION_ANNOTATED_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> ALL_VERSION_LIGHTWEIGHT_TAGS = new ArrayList<>(rawTags.size());
            List<Ref> HEAD_TAGS = new ArrayList<>();
            List<Ref> HEAD_ANNOTATED_TAGS = new ArrayList<>();
            List<Ref> HEAD_LIGHTWEIGHT_TAGS = new ArrayList<>();
            List<Ref> HEAD_VERSION_TAGS = new ArrayList<>();
            List<Ref> HEAD_VERSION_ANNOTATED_TAGS = new ArrayList<>();
            List<Ref> HEAD_VERSION_LIGHTWEIGHT_TAGS = new ArrayList<>();

            // retrieve all tags matching a version, and get all info for each of them
            for (Ref rawTag : rawTags) {
                Ref r = peel(rawTag);
                ALL_TAGS.add(r);
                switch ((GitUtils.tagOf(r, rootId) ? 4 : 0) + (strategy.isVersionTag(r) ? 2 : 0) + (GitUtils.isAnnotated(r) ? 1 : 0)) {
                case 0b110:
                    HEAD_TAGS.add(r);
                    HEAD_VERSION_TAGS.add(r);
                    HEAD_LIGHTWEIGHT_TAGS.add(r);
                    HEAD_VERSION_LIGHTWEIGHT_TAGS.add(r);
                case 0b010:
                    ALL_VERSION_TAGS.add(r);
                    ALL_VERSION_LIGHTWEIGHT_TAGS.add(r);
                case 0b000:
                    ALL_LIGHTWEIGHT_TAGS.add(r);
                    break;

                case 0b111:
                    HEAD_TAGS.add(r);
                    HEAD_VERSION_TAGS.add(r);
                    HEAD_ANNOTATED_TAGS.add(r);
                    HEAD_VERSION_ANNOTATED_TAGS.add(r);
                case 0b011:
                    ALL_VERSION_TAGS.add(r);
                    ALL_VERSION_ANNOTATED_TAGS.add(r);
                case 0b001:
                    ALL_ANNOTATED_TAGS.add(r);
                    break;

                case 0b101:
                    HEAD_ANNOTATED_TAGS.add(r);
                    ALL_ANNOTATED_TAGS.add(r);
                case 0b100:
                    HEAD_TAGS.add(r);
                    break;
                }
            };

            metadatas.registerMetadataTags(Metadatas.ALL_TAGS, ALL_TAGS);
            metadatas.registerMetadataTags(Metadatas.ALL_ANNOTATED_TAGS, ALL_ANNOTATED_TAGS);
            metadatas.registerMetadataTags(Metadatas.ALL_LIGHTWEIGHT_TAGS, ALL_LIGHTWEIGHT_TAGS);

            metadatas.registerMetadataTags(Metadatas.ALL_VERSION_TAGS, ALL_VERSION_TAGS);
            metadatas.registerMetadataTags(Metadatas.ALL_VERSION_ANNOTATED_TAGS, ALL_VERSION_ANNOTATED_TAGS);
            metadatas.registerMetadataTags(Metadatas.ALL_VERSION_LIGHTWEIGHT_TAGS, ALL_VERSION_LIGHTWEIGHT_TAGS);

            // handle a call on an empty git repository
            if (rootId == null) {
                // no HEAD exist
                // the GIT repo might just be initialized without any commit
                return Version.EMPTY_REPOSITORY_VERSION;
            }

            git.log().add(rootId).setMaxCount(1).call().spliterator().tryAdvance(rc -> {
                PersonIdent commitInfo = rc.getAuthorIdent();
                metadatas.registerMetadata(Metadatas.HEAD_COMMITTER_NAME, commitInfo.getName());
                metadatas.registerMetadata(Metadatas.HEAD_COMMITER_EMAIL, commitInfo.getEmailAddress());
                dtfmt.setTimeZone(commitInfo.getTimeZone());
                metadatas.registerMetadata(Metadatas.HEAD_COMMIT_DATETIME, dtfmt.format(commitInfo.getWhen()));
            });

            metadatas.registerMetadataTags(Metadatas.HEAD_TAGS, HEAD_TAGS);
            metadatas.registerMetadataTags(Metadatas.HEAD_ANNOTATED_TAGS, HEAD_ANNOTATED_TAGS);
            metadatas.registerMetadataTags(Metadatas.HEAD_LIGHTWEIGHT_TAGS, HEAD_LIGHTWEIGHT_TAGS);

            metadatas.registerMetadataTags(Metadatas.HEAD_VERSION_TAGS, HEAD_VERSION_TAGS);
            metadatas.registerMetadataTags(Metadatas.HEAD_VERSION_ANNOTATED_TAGS, HEAD_VERSION_ANNOTATED_TAGS);
            metadatas.registerMetadataTags(Metadatas.HEAD_VERSION_LIGHTWEIGHT_TAGS, HEAD_VERSION_LIGHTWEIGHT_TAGS);

            metadatas.registerMetadata(Metadatas.GIT_SHA1_FULL, rootId.getName());
            metadatas.registerMetadata(Metadatas.GIT_SHA1_8, rootId.getName().substring(0, 8));
            
            Commit head = new Commit(rootId, 0, HEAD_VERSION_ANNOTATED_TAGS, HEAD_VERSION_LIGHTWEIGHT_TAGS);
            
            Commit baseCommit = findBaseCommitFromReachableTags(rootId, ALL_VERSION_TAGS, ALL_VERSION_ANNOTATED_TAGS, ALL_VERSION_LIGHTWEIGHT_TAGS, maxDepth, strategy);

            if (baseCommit == null) {
                // it looks like not reachable commits from version tags were found
                // as we need at least one commit, let's find the deepest we can
                baseCommit = deepestReachableCommit(rootId, maxDepth);
            }

            Version calculatedVersion = strategy.build(head, Collections.singletonList(baseCommit));
            metadatas.registerMetadata(Metadatas.CALCULATED_VERSION, calculatedVersion.toString());

            // Calculated version could have the patch already incremented under conditions
            boolean patchVersionIsIncremented = hasPatchVersionBeenIncremented(strategy, calculatedVersion);
            provideNextVersionsMetadatas(calculatedVersion, patchVersionIsIncremented);

            return calculatedVersion;
        } catch (NoWorkTreeException ex) {
            // Could not find worktree and index - assuming this is a `git worktree` (this assumption should be logged as debug)
            return Version.NO_WORKTREE_AND_INDEX;
        } catch (Exception ex) {
            throw new IllegalStateException("failure calculating version", ex);
        }
    }

    private Commit deepestReachableCommit(ObjectId headId, int maxDepth) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit headCommit = repository.parseCommit(headId);
            revWalk.markStart(headCommit);
            int depth = 0;
            RevCommit lastCommit = headCommit;
            Iterator<RevCommit> iterator = revWalk.iterator();

            while (iterator.hasNext() && depth <= maxDepth) {
                lastCommit = iterator.next();
                depth++;
            }

            int retainedDepth = depth - 1;  // we do not count head
            return new Commit(lastCommit.getId(), retainedDepth, Collections.emptyList(), Collections.emptyList());
        }
    }

    private Commit findBaseCommitFromReachableTags(
            ObjectId headId,
            List<Ref> allVersionTags,
            List<Ref> normals,
            List<Ref> lights,
            int maxDepth,
            VersionStrategy strategy
    ) throws Exception {
        Stream<Ref> reachableTags = filterReachableTags(headId, allVersionTags);

        // see https://github.com/jgitver/jgitver/issues/73
        // light tags do not have a date information
        // we keep only annotated ones
        if (lookupPolicy == LookupPolicy.LATEST) {
            reachableTags = keepOnlyAnnotatedTags(reachableTags);
        }

        return findBaseCommitId(headId, reachableTags, lookupPolicy, strategy)
          .flatMap(baseCommitId -> {
              if (headId.getName().equals(baseCommitId.getName())) {
                  return Optional.of(new Commit(baseCommitId, 0, tagsOf(normals, baseCommitId), tagsOf(lights, baseCommitId)));
              } else {
                  DistanceCalculator distanceCalculator = DistanceCalculator.create(headId, repository, maxDepth);
                  return distanceCalculator.distanceTo(baseCommitId)
                    .map(distance ->
                           new Commit(baseCommitId, distance, tagsOf(normals, baseCommitId), tagsOf(lights, baseCommitId))
                    );
              }
          }).orElse(null);

    }

    private Stream<Ref> keepOnlyAnnotatedTags(Stream<Ref> reachableTags) {
        return reachableTags.filter(GitUtils::isAnnotated);
    }

    private static <T,C extends Comparable<C>> BinaryOperator<T> statefulMax(Function<T, C> comparable) {
        return new BinaryOperator<T>() {
            private C maxValue;
            @Override
            public T apply(T t1, T t2) {
                C c1;
                if (maxValue==null) {
                    c1 = comparable.apply(t1);
                } else {
                    c1 = maxValue;
                }

                C c2 = comparable.apply(t2);
                if (c1.compareTo(c2) >= 0) {
                    maxValue = c1;
                    return t1;
                } else {
                    maxValue = c2;
                    return t2;
                }
            }
        };
    }

    private Optional<ObjectId> findBaseCommitId(ObjectId headId, Stream<Ref> reachableTags, LookupPolicy lookupPolicy, VersionStrategy strategy) {
        switch (lookupPolicy) {
            case MAX:
                return reachableTags
                  .reduce(statefulMax(strategy::versionFromTag))
                  .map(refToObjectIdFunction);
            case LATEST:
                return latestObjectIdOfTags(reachableTags).map(refToObjectIdFunction);
            case NEAREST:
                DistanceCalculator dc = DistanceCalculator.create(headId, repository);

                Map<Integer, List<Ref>> tagsByDistance = reachableTags
                        .collect(Collectors.groupingBy(r -> dc.distanceTo(refToObjectIdFunction.apply(r)).get()));

                Integer minimumDistance = Collections.min(tagsByDistance.keySet());
                List<Ref> tagsAtMinimumDistance = tagsByDistance.get(minimumDistance);

                if (tagsAtMinimumDistance.size() == 1) {
                    return Optional.of(refToObjectIdFunction.apply(tagsAtMinimumDistance.get(0)));
                } else {
                    // we take the most recent one among those at the same distance
                    return latestObjectIdOfTags(tagsAtMinimumDistance.stream()).map(refToObjectIdFunction);
                }
            default:
                throw new IllegalStateException(String.format("[%s] lookup policy is not implmented", lookupPolicy));
        }
    }

    private Optional<Ref> latestObjectIdOfTags(Stream<Ref> reachableTags) {
        try (TagDateExtractor dateExtractor = new TagDateExtractor(repository)) {
            return reachableTags.reduce(statefulMax(dateExtractor::dateOfRef));
        }
    }

    /**
     * Filters the given list of tags based on their reachability starting from the given commit.
     * It returns a new non null List.
     */
    private Stream<Ref> filterReachableTags(ObjectId headId, List<Ref> allVersionTags) throws IOException {
        List<Ref> filtered = new ArrayList<>(allVersionTags.size());

        // Pre-calculate multimap of commit -> [tags] (O(N*M) -> O(N))
        Map<String, List<Ref>> taggedCommits = allVersionTags.stream().collect(Collectors.groupingBy(r -> refToObjectIdFunction.apply(r).getName()));

        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(headId));

            for (RevCommit revCommit : walk) {
                List<Ref> tagsForThisCommit = taggedCommits.get(revCommit.getId().getName());
                if (tagsForThisCommit != null) {
                    filtered.addAll(tagsForThisCommit);
                }
            }
        }

        return filtered.stream();
    }

    private Ref peel(Ref tag) {
        try {
            return repository.getRefDatabase().peel(tag);
        } catch (IOException e) {
            return tag;
        }
    }

    @Override
    public void close() throws Exception {
        if (repository != null) {
            repository.close();
        }
    }

    @Override
    public GitVersionCalculator setAutoIncrementPatch(boolean value) {
        this.autoIncrementPatch = value;
        return this;
    }

    @Override
    public GitVersionCalculator setNonQualifierBranches(String nonQualifierBranches) {
        List<BranchingPolicy> branchPolicies = new LinkedList<>();
        
        if (nonQualifierBranches != null && !"".equals(nonQualifierBranches.trim())) {
            for (String branch : nonQualifierBranches.split(",")) {
                branchPolicies.add(
                        BranchingPolicy.fixedBranchName(
                                branch, 
                                Collections.singletonList(BranchNameTransformations.IGNORE.name())
                        )
                );
            }
        }
        
        return setQualifierBranchingPolicies(branchPolicies);
    }
    
    @Override
    public GitVersionCalculator setQualifierBranchingPolicies(BranchingPolicy... policies) {
        return setQualifierBranchingPolicies(Arrays.asList(policies));
    }
    
    @Override
    public GitVersionCalculator setQualifierBranchingPolicies(List<BranchingPolicy> policies) {
        if (policies != null) {
            this.qualifierBranchingPolicies = new LinkedList<>(policies);
        }
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseDistance(boolean useDistance) {
        this.useDistance = useDistance;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseDirty(boolean useDirty) {
        this.useDirty = useDirty;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseLongFormat(boolean useLongFormat) {
        this.useLongFormat = useLongFormat;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseGitCommitId(boolean useGitCommitId) {
        this.useGitCommitId = useGitCommitId;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseGitCommitTimestamp(boolean useGitCommitTimestamp) {
        this.useGitCommitTimestamp = useGitCommitTimestamp;
        computationRequired = true;
        return this;
    }
    
    @Override
    public GitVersionCalculator setUseDefaultBranchingPolicy(boolean useDefaultBranchingPolicy) {
        this.useDefaultBranchingPolicy = useDefaultBranchingPolicy;
        computationRequired = true;
        return this;
    }
    

    @Override
    public GitVersionCalculator setGitCommitIdLength(int gitCommitIdLength) {
        if (gitCommitIdLength < 8 || gitCommitIdLength > 40) {
            throw new IllegalStateException("GitCommitIdLength must be between 8 & 40");
        }
        this.gitCommitIdLength = gitCommitIdLength;
        computationRequired = true;
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public GitVersionCalculator setMavenLike(boolean mavenLike) {
        this.mavenLike = mavenLike;
        computationRequired = true;
        return this;
    }
    
    @Override
    public GitVersionCalculator setFindTagVersionPattern(String pattern) {
        this.findTagVersionPattern = Pattern.compile(pattern);
        computationRequired = true;
        return this;
    }

    @Override
    public Optional<String> meta(Metadatas meta) {
        if (metadatas == null) {
            getVersion();
        }
        return metadatas.meta(meta);
    }

    @Override
    public GitVersionCalculator setStrategy(Strategies s) {
        this.versionStrategy = Objects.requireNonNull(s, "provided strategy cannot be null");
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setTagVersionPattern(String pattern) {
        this.tagVersionPattern = pattern;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setVersionPattern(String pattern) {
        this.versionPattern = pattern;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setLookupPolicy(LookupPolicy policy) {
        this.lookupPolicy = policy;
        this.computationRequired = true;
        return this;
    }

    @Override
    public GitVersionCalculator setUseSnapshot(boolean useSnapshot) {
        this.useSnapshot = useSnapshot;
        return this;
    }

    @Override
    public GitVersionCalculator setForceComputation(boolean forceComputation) {
        this.forceComputation = forceComputation;
        return this;
    }

    @Override
    public GitVersionCalculator setScriptType(ScriptType scriptType) {
        this.scriptType = scriptType;
        return this;
    }

    @Override
    public GitVersionCalculator setScript(String script) {
        this.script = script;
        return this;
    }
}
