package org.nd4j.linalg.memory.provider;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.MemoryWorkspaceManager;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.*;
import org.nd4j.linalg.api.memory.pointers.PagedPointer;
import org.nd4j.linalg.api.memory.pointers.PointersPair;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.memory.abstracts.DummyWorkspace;
import org.nd4j.linalg.memory.abstracts.Nd4jWorkspace;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Workspace manager implementation. Please note, this class is supposed to be used via Nd4j.getWorkspaceManager(), to provide consistency between different threads within given JVM process
 * @author raver119@gmail.com
 */
@Slf4j
public abstract class BasicWorkspaceManager implements MemoryWorkspaceManager {

    protected WorkspaceConfiguration defaultConfiguration;
    protected ThreadLocal<Map<String, MemoryWorkspace>> backingMap = new ThreadLocal<>();
    private ReferenceQueue<MemoryWorkspace> queue;
    private WorkspaceDeallocatorThread thread;
    private Map<String, Nd4jWorkspace.GarbageWorkspaceReference> referenceMap = new ConcurrentHashMap<>();

    public BasicWorkspaceManager() {
        this(WorkspaceConfiguration.builder().initialSize(0).maxSize(0).overallocationLimit(0.3).policyAllocation(AllocationPolicy.OVERALLOCATE).policyLearning(LearningPolicy.FIRST_LOOP).policyMirroring(MirroringPolicy.FULL).policySpill(SpillPolicy.EXTERNAL).build());
    }

    public BasicWorkspaceManager(@NonNull WorkspaceConfiguration defaultConfiguration) {
        this.defaultConfiguration = defaultConfiguration;
        this.queue = new ReferenceQueue<>();

        thread = new WorkspaceDeallocatorThread(this.queue);
        thread.start();
    }

    /**
     * This method allows to specify "Default" configuration, that will be used in signatures which do not have WorkspaceConfiguration argument
     * @param configuration
     */
    @Override
    public void setDefaultWorkspaceConfiguration(@NonNull WorkspaceConfiguration configuration) {
        this.defaultConfiguration = configuration;
    }

    /**
     * This method will return workspace with default configuration and default id.
     * @return
     */
    @Override
    public MemoryWorkspace getWorkspaceForCurrentThread() {
        return getWorkspaceForCurrentThread(MemoryWorkspace.DEFAULT_ID);
    }

    @Override
    public MemoryWorkspace getWorkspaceForCurrentThread(@NonNull String id) {
        return getWorkspaceForCurrentThread(defaultConfiguration, id);
    }

    /*
    @Override
    public MemoryWorkspace getWorkspaceForCurrentThread(@NonNull WorkspaceConfiguration configuration, @NonNull String id) {
        ensureThreadExistense();

        MemoryWorkspace workspace = backingMap.get().get(id);
        if (workspace == null) {
            workspace = new Nd4jWorkspace(configuration, id);
            backingMap.get().put(id, workspace);
        }

        return workspace;
    }
    */

    protected void pickReference(MemoryWorkspace workspace) {
        Nd4jWorkspace.GarbageWorkspaceReference reference = new Nd4jWorkspace.GarbageWorkspaceReference(workspace, queue);
        referenceMap.put(reference.getId()+ "_" + reference.getThreadId(), reference);
    }

    @Override
    public void setWorkspaceForCurrentThread(MemoryWorkspace workspace) {
        setWorkspaceForCurrentThread(workspace, MemoryWorkspace.DEFAULT_ID);
    }

    @Override
    public void setWorkspaceForCurrentThread(@NonNull MemoryWorkspace workspace, @NonNull String id) {
        ensureThreadExistense();

        backingMap.get().put(id, workspace);
    }

    /**
     * This method destroys given workspace
     *
     * @param workspace
     */
    @Override
    public void destroyWorkspace(MemoryWorkspace workspace) {
        if (workspace == null || workspace instanceof DummyWorkspace)
            return;

        //workspace.destroyWorkspace();
        backingMap.get().remove(workspace.getId());
    }

    /**
     * This method destroy default workspace, if any
     */
    @Override
    public void destroyWorkspace() {
        ensureThreadExistense();

        MemoryWorkspace workspace = backingMap.get().get(MemoryWorkspace.DEFAULT_ID);
        //if (workspace != null)
            //workspace.destroyWorkspace();

        backingMap.get().remove(MemoryWorkspace.DEFAULT_ID);
    }

    /**
     * This method destorys all workspaces allocated in current thread
     */
    @Override
    public void destroyAllWorkspacesForCurrentThread() {
        ensureThreadExistense();

        List<MemoryWorkspace> workspaces = new ArrayList<>();
        workspaces.addAll(backingMap.get().values());

        for (MemoryWorkspace workspace: workspaces) {
            destroyWorkspace(workspace);
        }

        System.gc();
    }

    protected void ensureThreadExistense() {
        if (backingMap.get() == null)
            backingMap.set(new HashMap<String, MemoryWorkspace>());
    }

    /**
     * This method gets & activates default workspace
     *
     * @return
     */
    @Override
    public MemoryWorkspace getAndActivateWorkspace() {
        return getWorkspaceForCurrentThread().notifyScopeEntered();
    }

    /**
     * This method gets & activates workspace with a given Id
     *
     * @param id
     * @return
     */
    @Override
    public MemoryWorkspace getAndActivateWorkspace(@NonNull String id) {
        return getWorkspaceForCurrentThread(id).notifyScopeEntered();
    }

    /**
     * This method gets & activates default with a given configuration and Id
     *
     * @param configuration
     * @param id
     * @return
     */
    @Override
    public MemoryWorkspace getAndActivateWorkspace(@NonNull WorkspaceConfiguration configuration,@NonNull String id) {
        return getWorkspaceForCurrentThread(configuration, id).notifyScopeEntered();
    }

    /**
     * This method checks, if Workspace with a given Id was created before this call
     *
     * @param id
     * @return
     */
    @Override
    public boolean checkIfWorkspaceExists(@NonNull String id) {
        ensureThreadExistense();
        return backingMap.get().containsKey(id);
    }


    /**
     * This method temporary opens block out of any workspace scope.
     * <p>
     * PLEASE NOTE: Do not forget to close this block.
     *
     * @return
     */
    @Override
    public MemoryWorkspace scopeOutOfWorkspaces() {
        MemoryWorkspace workspace = Nd4j.getMemoryManager().getCurrentWorkspace();
        if (workspace == null)
            return new DummyWorkspace();
        else {
            Nd4j.getMemoryManager().setCurrentWorkspace(null);
            return workspace.tagOutOfScopeUse();
        }
    }


    protected class WorkspaceDeallocatorThread extends Thread implements Runnable {
        private final ReferenceQueue<MemoryWorkspace> queue;

        protected WorkspaceDeallocatorThread(ReferenceQueue<MemoryWorkspace> queue) {
            this.queue = queue;
            this.setDaemon(true);
            this.setName("Workspace deallocator thread");
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Nd4jWorkspace.GarbageWorkspaceReference reference = (Nd4jWorkspace.GarbageWorkspaceReference) queue.remove();
                    if (reference != null) {
//                      log.info("Releasing reference for Workspace [{}]", reference.getId());
                        PointersPair pair = reference.getPointersPair();
                        // purging workspace planes
                        if (pair!= null) {
                            if (pair.getDevicePointer() != null) {
                                //log.info("Deallocating device...");
                                Nd4j.getMemoryManager().release(pair.getDevicePointer(), MemoryKind.DEVICE);
                            }


                            if (pair.getHostPointer() != null) {
//                                log.info("Deallocating host...");
                                referenceMap.remove(reference.getId() + "_" + reference.getThreadId());
                                Nd4j.getMemoryManager().release(pair.getHostPointer(), MemoryKind.HOST);
                            }
                        }

                        // purging all spilled pointers
                        for (PointersPair pair2 : reference.getExternalPointers()) {
                            if (pair2 != null) {
                                if (pair2.getHostPointer() != null)
                                    Nd4j.getMemoryManager().release(pair2.getHostPointer(), MemoryKind.HOST);

                                if (pair2.getDevicePointer() != null)
                                    Nd4j.getMemoryManager().release(pair2.getDevicePointer(), MemoryKind.DEVICE);
                            }
                        }

                        // purging all pinned pointers
                        while ((pair = reference.getPinnedPointers().poll()) != null) {
                            if (pair.getHostPointer() != null)
                                Nd4j.getMemoryManager().release(pair.getHostPointer(), MemoryKind.HOST);

                            if (pair.getDevicePointer() != null)
                                Nd4j.getMemoryManager().release(pair.getDevicePointer(), MemoryKind.DEVICE);
                        }
                    }
                } catch (Exception e) {
                    //
                }
            }
        }
    }

    /**
     * This method prints out basic statistics for workspaces allocated in current thread
     */
    public synchronized void printAllocationStatisticsForCurrentThread() {
        ensureThreadExistense();
        Map<String, MemoryWorkspace> map = backingMap.get();
        log.info("Workspace statistics: ---------------------------------");
        log.info("Number of workspaces in current thread: {}", map.size());
        for (String key : map.keySet()) {
            log.info("Workspace: {}", key);
            log.info("Allocated amount: {} bytes", ((Nd4jWorkspace)map.get(key)).getCurrentSize());
            log.info("External (spilled) amount: {} bytes", ((Nd4jWorkspace)map.get(key)).getSpilledSize());
            log.info("External (pinned) amount: {} bytes", ((Nd4jWorkspace)map.get(key)).getPinnedSize());
            System.out.println();
        }
    }
}
