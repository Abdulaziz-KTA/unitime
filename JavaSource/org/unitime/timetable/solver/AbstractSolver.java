package org.unitime.timetable.solver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cpsolver.ifs.assignment.Assignment;
import org.cpsolver.ifs.model.Model;
import org.cpsolver.ifs.model.Value;
import org.cpsolver.ifs.model.Variable;
import org.cpsolver.ifs.solution.Solution;
import org.cpsolver.ifs.solver.ParallelSolver;
import org.cpsolver.ifs.solver.Solver;
import org.cpsolver.ifs.util.Callback;
import org.cpsolver.ifs.util.DataProperties;
import org.cpsolver.ifs.util.ProblemLoader;
import org.cpsolver.ifs.util.ProblemSaver;
import org.cpsolver.ifs.util.Progress;
import org.cpsolver.ifs.util.ProgressWriter;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.solver.remote.BackupFileFilter;
import org.unitime.timetable.util.Constants;

public abstract class AbstractSolver<V extends Variable<V, T>, T extends Value<V, T>, M extends Model<V, T>> extends ParallelSolver<V, T> implements CommonSolverInterface{
    protected Log sLog = null;
    private int iDebugLevel = Progress.MSGLEVEL_INFO;
    protected boolean iWorking = false;
    protected Date iLoadedDate = null;
    private SolverDisposeListener iDisposeListener = null;
    
    private long iLastTimeStamp = System.currentTimeMillis();
    private boolean iIsPassivated = false;
    private Map iProgressBeforePassivation = null;
    private Map<String,String> iCurrentSolutionInfoBeforePassivation = null;
    private Map<String,String> iBestSolutionInfoBeforePassivation = null;
    private File iPassivationFolder = null;
    private String iPassivationPuid = null;
    protected Thread iWorkThread = null;
    
    public AbstractSolver(DataProperties properties, SolverDisposeListener disposeListener) {
        super(properties);
        iDisposeListener = disposeListener;
        sLog = LogFactory.getLog(getClass());
    }
    
    @Override
    public Date getLoadedDate() {
        if (iLoadedDate==null && !isPassivated()) {
            List<Progress.Message> log = Progress.getInstance(currentSolution().getModel()).getLog();
            if (log!=null && !log.isEmpty()) {
                iLoadedDate = log.get(0).getDate();
            }
        }
        return iLoadedDate;
    }

    @Override
    public String getLog() {
        return Progress.getInstance(currentSolution().getModel()).getHtmlLog(iDebugLevel, true);
    }

    @Override
    public String getLog(int level, boolean includeDate) {
        return Progress.getInstance(currentSolution().getModel()).getHtmlLog(level, includeDate);
    }

    @Override
    public String getLog(int level, boolean includeDate, String fromStage) {
        return Progress.getInstance(currentSolution().getModel()).getHtmlLog(level, includeDate, fromStage);
    }

    @Override
    public void setDebugLevel(int level) { iDebugLevel = level; }

    @Override
    public int getDebugLevel() { return iDebugLevel; }
    
    @Override
    public boolean isWorking() {
        if (isRunning()) return true;
        return iWorking;
    }

    @Override
    public void restoreBest() {
        currentSolution().restoreBest();
    }

    @Override
    public void saveBest() {
        currentSolution().saveBest();
    }

    @Override
    public Map getProgress() {
        if (isPassivated()) return iProgressBeforePassivation;
        try {
            Hashtable ret = new Hashtable(); 
            Progress p = Progress.getInstance(super.currentSolution().getModel());
            ret.put("STATUS",p.getStatus());
            ret.put("PHASE",p.getPhase());
            ret.put("PROGRESS",new Long(p.getProgress()));
            ret.put("MAX_PROGRESS",new Long(p.getProgressMax()));
            ret.put("VERSION", Constants.getVersion());
            return ret;
        } catch (Exception e) {
            sLog.error(e.getMessage(),e);
            return null;
        }
    }
    
    @Override
    public void setProperties(DataProperties properties) {
        activateIfNeeded();
        this.getProperties().putAll(properties);
    }
    
    @Override
    public void dispose() {
        disposeNoInherit(true);
    }

    protected void disposeNoInherit(boolean unregister) {
        super.dispose();
        if (currentSolution()!=null && currentSolution().getModel()!=null)
            Progress.removeInstance(currentSolution().getModel());
        setInitalSolution((org.cpsolver.ifs.solution.Solution)null);
        if (unregister && iDisposeListener!=null) iDisposeListener.onDispose();
    }
    
    @Override
    public String getHost() {
        return "local";
    }
    
    @Override
    public String getUser() {
    	return getProperties().getProperty("General.OwnerPuid");
    }
    
    @Override
    public Map<String,String> currentSolutionInfo() {
        if (isPassivated()) return iCurrentSolutionInfoBeforePassivation;
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
            return super.currentSolution().getInfo();
        } finally {
        	lock.unlock();
        }
    }

    @Override
    public Map<String,String> bestSolutionInfo() {
        if (isPassivated()) return iBestSolutionInfoBeforePassivation;
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
            return super.currentSolution().getBestInfo();
        } finally {
        	lock.unlock();
        }
    }
    
    protected abstract ProblemSaver<V, T, M> getDatabaseSaver(Solver<V, T> solver);
    protected abstract ProblemLoader<V, T, M> getDatabaseLoader(M model, Assignment<V, T> assignment);

    protected void finishBeforeSave() {}
    
    @Override
    protected void onFinish() {
        super.onFinish();
        try {
            iWorking = true;
            if (currentSolution().getBestInfo()!=null)
                currentSolution().restoreBest();
            finishBeforeSave();
            if (currentSolution().getBestInfo()!=null && getProperties().getPropertyBoolean("General.Save",false)) {
            	ProblemSaver<V, T, M> saver = getDatabaseSaver(this);
                Lock lock = currentSolution().getLock().readLock();
                lock.lock();
                try {
                    saver.save();
                } catch (Exception e) {
                	sLog.error("Failed to save the problem: " + e.getMessage(), e);
                } finally {
                	lock.unlock();
                }
            }
            if (getProperties().getPropertyBoolean("General.Unload",false)) {
                dispose();
            } else {
                Progress.getInstance(currentSolution().getModel()).setStatus("Awaiting commands ...");
            }
        } finally {
            iWorking = false;
        }
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        if (currentSolution().getBestInfo()!=null)
            currentSolution().restoreBest();
    }

    @Override
    public void save() {
        iWorking = true;
        ProblemSaver<V, T, M> saver = getDatabaseSaver(this);
        saver.setCallback(getSavingDoneCallback());
        iWorkThread = new Thread(saver);
        iWorkThread.setPriority(THREAD_PRIORITY);
        iWorkThread.start();
    }
    
    protected abstract M createModel(DataProperties properties);
    
    @Override
    public void load(DataProperties properties) {
        setProperties(properties);
        M model = createModel(properties);
        Progress.getInstance(model).addProgressListener(new ProgressWriter(System.out));
        
        iWorking = true;
        setInitalSolution(model);
        initSolver();
        
        ProblemLoader<V, T, M> loader = getDatabaseLoader(model, currentSolution().getAssignment());
        loader.setCallback(getLoadingDoneCallback());
        iWorkThread = new Thread(loader);
        iWorkThread.setPriority(THREAD_PRIORITY);
        iWorkThread.start();
    }
    
    @Override
    public void reload(DataProperties properties) {
        if (currentSolution()==null || currentSolution().getModel()==null) {
            load(properties);
            return;
        }
        
        Callback callBack = getReloadingDoneCallback();
        setProperties(properties);
        M model = createModel(properties);
        
        iWorking = true;
        Progress.changeInstance(currentSolution().getModel(),model);
        setInitalSolution(model);
        initSolver();
        
        ProblemLoader<V, T, M> loader = getDatabaseLoader(model, currentSolution().getAssignment());
        loader.setCallback(callBack);
        iWorkThread = new Thread(loader);
        iWorkThread.start();
    }
    
    public Callback getLoadingDoneCallback() {
        return new DefaultLoadingDoneCallback();
    }
    
    public abstract Callback getReloadingDoneCallback();

    public Callback getSavingDoneCallback() {
        return new DefaultSavingDoneCallback();
    }
    
    protected void afterSave() {
    }
    
    protected void afterLoad() {
    }

    public class DefaultLoadingDoneCallback implements Callback {
        public void execute() {
            iLoadedDate = new Date();
            iWorking = false;
            afterLoad();
            Progress.getInstance(currentSolution().getModel()).setStatus("Awaiting commands ...");
            if (getProperties().getPropertyBoolean("General.StartSolver",false))
                start();
        }
    }
    
    public class DefaultSavingDoneCallback implements Callback {
        public void execute() {
            iWorking = false;
            afterSave();
            Progress.getInstance(currentSolution().getModel()).setStatus("Awaiting commands ...");
        }
    }
    
    protected abstract Document createCurrentSolutionBackup(boolean anonymize, boolean idconv);
    
    protected void saveProperties(Document document) {
    	Element configuration = document.getRootElement().addElement("configuration");
    	for (Map.Entry e: getProperties().entrySet()) {
    		configuration.addElement("property").addAttribute("name", e.getKey().toString()).setText(e.getValue().toString());
    	}
    }
    
    protected void readProperties(Document document) {
    	Element configuration = document.getRootElement().element("configuration");
    	if (configuration != null)
    		for (Iterator i = configuration.elementIterator("property"); i.hasNext(); ) {
    			Element e = (Element)i.next();
    			getProperties().setProperty(e.attributeValue("name"), e.getText());
    		}
    }
    
    @Override
    public boolean backup(File folder, String puid) {
        folder.mkdirs();
        if (currentSolution()==null) return false;
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
            File outXmlFile = new File(folder, getType().getPrefix() + puid + BackupFileFilter.sXmlExtension);
            try {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(outXmlFile);
                    Document document = createCurrentSolutionBackup(false, false);
                    saveProperties(document);
                    (new XMLWriter(fos,OutputFormat.createPrettyPrint())).write(document);
                    fos.flush(); fos.close(); fos=null;
                } finally {
                    try {
                        if (fos!=null) fos.close();
                    } catch (IOException e) {}
                }
                return true;
            } catch (Exception e) {
                sLog.error(e.getMessage(),e);
                if (outXmlFile.exists()) outXmlFile.delete();
            }
        } finally {
        	lock.unlock();
        }
        return false;
    }
    
    @Override
    public boolean restore(File folder, String puid) {
        return restore(folder, puid, false);
    }
    
    protected abstract void restureCurrentSolutionFromBackup(Document document);
    
    @Override
    public boolean restore(File folder, String puid, boolean removeFiles) {
        sLog.debug("restore(folder="+folder+","+puid+")");
        File inXmlFile = new File(folder,getType().getPrefix() + puid + BackupFileFilter.sXmlExtension);
        
        M model = null;
        try {
            if (isRunning()) stopSolver();
            disposeNoInherit(false);

            Document document = (new SAXReader()).read(inXmlFile);
            readProperties(document);
            
            model = createModel(getProperties());
            Progress.getInstance(model).addProgressListener(new ProgressWriter(System.out));
            setInitalSolution(model);
            initSolver();

            restureCurrentSolutionFromBackup(document);
            Progress.getInstance(model).setStatus("Awaiting commands ...");
            
            if (removeFiles) {
                inXmlFile.delete();
            }
            
            return true;
        } catch (Exception e) {
            sLog.error(e.getMessage(),e);
            if (model!=null) Progress.removeInstance(model);
        }
        
        return false;
    }
    
    @Override
    public void clear() {
        Lock lock = currentSolution().getLock().writeLock();
        lock.lock();
        try {
            for (V v: currentSolution().getModel().variables()) {
            	currentSolution().getAssignment().unassign(0, v);
            }
            currentSolution().clearBest();
        } finally {
        	lock.unlock();
        }
    }

    @Override
    public Long getSessionId() {
        return getProperties().getPropertyLong("General.SessionId", null);
    }

    @Override
    public Solution<V, T> currentSolution() {
        activateIfNeeded();
        return super.currentSolution();
    }
    
    protected void beforeStart() {}
    
    @Override
    public void start() {
        activateIfNeeded();
        beforeStart();
        super.start();
    }
    
    @Override
    public synchronized boolean isPassivated() {
        return iIsPassivated;
    }
    
    @Override
    public synchronized long timeFromLastUsed() {
        return System.currentTimeMillis()-iLastTimeStamp;
    }
    
    @Override
    public synchronized boolean activateIfNeeded() {
        iLastTimeStamp = System.currentTimeMillis();
        if (!isPassivated()) return false;
        sLog.debug("<activate "+iPassivationPuid+">");

        iIsPassivated = false;

        System.gc();
        sLog.debug(" -- memory usage before activation:"+org.unitime.commons.Debug.getMem());
        restore(iPassivationFolder, iPassivationPuid, true);
        System.gc();
        sLog.debug(" -- memory usage after activation:"+org.unitime.commons.Debug.getMem());
        
        return true;
    }
    
    @Override
    public synchronized boolean passivate(File folder, String puid) {
        if (isPassivated() || super.currentSolution()==null || super.currentSolution().getModel()==null) return false;
        sLog.debug("<passivate "+puid+">");
        System.gc();
        sLog.debug(" -- memory usage before passivation:"+org.unitime.commons.Debug.getMem());
        iProgressBeforePassivation = getProgress();
        if (iProgressBeforePassivation!=null)
            iProgressBeforePassivation.put("STATUS","Pasivated");
        iCurrentSolutionInfoBeforePassivation = currentSolutionInfo();
        iBestSolutionInfoBeforePassivation = bestSolutionInfo();
        
        iPassivationFolder = folder;
        iPassivationPuid = puid;
        backup(iPassivationFolder, iPassivationPuid);

        disposeNoInherit(false);
        
        System.gc();
        sLog.debug(" -- memory usage after passivation:"+org.unitime.commons.Debug.getMem());
        
        iIsPassivated = true;
        return true;
    }

    @Override
    public synchronized boolean passivateIfNeeded(File folder, String puid) {
		long inactiveTimeToPassivate = 60000l * ApplicationProperty.SolverPasivationTime.intValue();
		if (isPassivated() || inactiveTimeToPassivate <= 0 || timeFromLastUsed() < inactiveTimeToPassivate || isWorking()) return false;
        return passivate(folder, puid);
    }
    
    @Override
    public Date getLastUsed() {
        return new Date(iLastTimeStamp);
    }
    
    @Override
    public void interrupt() {
    	try {
            if (iSolverThread != null) {
                iStop = true;
                if (iSolverThread.isAlive() && !iSolverThread.isInterrupted())
                	iSolverThread.interrupt();
            }
			if (iWorkThread != null && iWorkThread.isAlive() && !iWorkThread.isInterrupted()) {
				iWorkThread.interrupt();
			}
    	} catch (Exception e) {
    		sLog.error("Unable to interrupt the solver, reason: " + e.getMessage(), e);
    	}
    }

    @Override
    public Map<String,String> statusSolutionInfo() {
    	if (isPassivated())
    		return (iBestSolutionInfoBeforePassivation == null ? iCurrentSolutionInfoBeforePassivation : iBestSolutionInfoBeforePassivation);
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
    		Map<String,String> info = super.currentSolution().getBestInfo();
    		try {
    			Solution<V, T> solution = getWorkingSolution();
    			if (info == null || getSolutionComparator().isBetterThanBestSolution(solution))
    				info = solution.getModel().getInfo(solution.getAssignment());
    		} catch (ConcurrentModificationException e) {}
    		return info;
        } finally {
        	lock.unlock();
    	}
    }
    
    @Override
    public byte[] exportXml() throws IOException {
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
            boolean anonymize = ApplicationProperty.SolverXMLExportNames.isFalse();
            boolean idconv = ApplicationProperty.SolverXMLExportConvertIds.isTrue();

            ByteArrayOutputStream ret = new ByteArrayOutputStream();
            
            Document document = createCurrentSolutionBackup(anonymize, idconv);
            
            if (ApplicationProperty.SolverXMLExportConfiguration.isTrue())
            	saveProperties(document);
            
            (new XMLWriter(ret, OutputFormat.createPrettyPrint())).write(document);
            
            ret.flush(); ret.close();

            return ret.toByteArray();
        } finally {
        	lock.unlock();
        }
    }

}