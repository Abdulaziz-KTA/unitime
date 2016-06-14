/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * The Apereo Foundation licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
*/
package org.unitime.timetable.solver.instructor;


import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cpsolver.coursett.Constants;
import org.cpsolver.coursett.model.TimeLocation;
import org.cpsolver.ifs.assignment.Assignment;
import org.cpsolver.ifs.model.Constraint;
import org.cpsolver.ifs.util.ProblemLoader;
import org.cpsolver.ifs.util.Progress;
import org.cpsolver.instructor.constraints.SameInstructorConstraint;
import org.cpsolver.instructor.model.Attribute;
import org.cpsolver.instructor.model.Course;
import org.cpsolver.instructor.model.Instructor;
import org.cpsolver.instructor.model.InstructorSchedulingModel;
import org.cpsolver.instructor.model.Preference;
import org.cpsolver.instructor.model.Section;
import org.cpsolver.instructor.model.TeachingAssignment;
import org.cpsolver.instructor.model.TeachingRequest;
import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.Transaction;
import org.unitime.timetable.model.ClassInstructor;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.DatePattern;
import org.unitime.timetable.model.Department;
import org.unitime.timetable.model.DepartmentalInstructor;
import org.unitime.timetable.model.DistributionPref;
import org.unitime.timetable.model.InstructorAttribute;
import org.unitime.timetable.model.InstructorAttributePref;
import org.unitime.timetable.model.InstructorCoursePref;
import org.unitime.timetable.model.InstructorPref;
import org.unitime.timetable.model.Location;
import org.unitime.timetable.model.PreferenceLevel;
import org.unitime.timetable.model.SchedulingSubpart;
import org.unitime.timetable.model.StudentClassEnrollment;
import org.unitime.timetable.model.TimePatternModel;
import org.unitime.timetable.model.TimePref;
import org.unitime.timetable.model.comparators.ClassComparator;
import org.unitime.timetable.model.dao.TimetableManagerDAO;
import org.unitime.timetable.util.NameFormat;

/**
 * @author Tomas Muller
 */
public class InstructorSchedulingDatabaseLoader extends ProblemLoader<TeachingRequest.Variable, TeachingAssignment, InstructorSchedulingModel> {
	private Progress iProgress = null;
	private Long iSessionId;
	private Set<Long> iSolverGroupId = new HashSet<Long>();
	private String iInstructorFormat;
	private Set<String> iCommonItypes = new HashSet<String>();
	private Map<Long, Attribute.Type> iAttributeTypes = new HashMap<Long, Attribute.Type>();
	private Map<Long, Attribute> iAttributes = new HashMap<Long, Attribute>();
	private Map<Long, Course> iCourses = new HashMap<Long, Course>();
	private Map<Long, Attribute> iDepartmentAttribute = new HashMap<Long, Attribute>();
	private Map<Long, Section> iSections = new HashMap<Long, Section>();
	private Map<Long, Instructor> iInstructors = new HashMap<Long, Instructor>();
	private boolean iHasTentative = false, iHasCommitted = false;
	
    public InstructorSchedulingDatabaseLoader(InstructorSchedulingModel model, Assignment<TeachingRequest.Variable, TeachingAssignment> assignment) {
    	super(model, assignment);
    	iProgress = Progress.getInstance(model);
    	iSessionId = model.getProperties().getPropertyLong("General.SessionId", (Long)null);
    	for (Long id: model.getProperties().getPropertyLongArry("General.SolverGroupId", null))
    		iSolverGroupId.add(id);
    	iInstructorFormat = getModel().getProperties().getProperty("General.InstructorFormat", NameFormat.LAST_FIRST.reference());
    	String commonItypes = getModel().getProperties().getProperty("General.CommonItypes", "lec");
    	if (commonItypes != null)
    		for (String itype: commonItypes.split(","))
    			if (!itype.isEmpty()) iCommonItypes.add(itype);
    }
    
    public void load() throws Exception {
    	org.hibernate.Session hibSession = null;
    	Transaction tx = null;
    	try {
    		hibSession = TimetableManagerDAO.getInstance().createNewSession();
    		hibSession.setCacheMode(CacheMode.IGNORE);
    		hibSession.setFlushMode(FlushMode.COMMIT);
    		
    		tx = hibSession.beginTransaction(); 
    		
    		load(hibSession);
    		
    		tx.commit();
    	} catch (Exception e) {
    		iProgress.fatal("Unable to load input data, reason: " + e.getMessage(), e);
    		tx.rollback();
    	} finally {
    		// here we need to close the session since this code may run in a separate thread
    		if (hibSession != null && hibSession.isOpen()) hibSession.close();
    	}
    }
    
    protected void load(org.hibernate.Session hibSession) throws Exception {
    	iProgress.setStatus("Loading input data ...");
    	List<Department> departments = (List<Department>)hibSession.createQuery(
    			"from Department d where d.solverGroup.uniqueId in :solverGroupId"
    			).setParameterList("solverGroupId", iSolverGroupId).list();
    	if (departments.size() > 1) {
    		Attribute.Type dt = new Attribute.Type(-1, "Department", false, false);
    		getModel().addAttributeType(dt);
    		for (Department d: departments)
    			iDepartmentAttribute.put(d.getUniqueId(), new Attribute(-d.getUniqueId(), d.getDeptCode(), dt));
    	}
    	
    	loadInstructors(hibSession);
    	
    	loadRequests(hibSession);
        
    	createAssignment();
        
        getModel().getProperties().setProperty("Save.Commit", iHasTentative || !iHasCommitted ? "false" : "true");
    }
    
    protected Attribute getAttribute(InstructorAttribute a) {
    	Attribute attribute = iAttributes.get(a.getUniqueId());
    	if (attribute == null) {
    		Attribute.Type type = iAttributeTypes.get(a.getType().getUniqueId());
    		if (type == null) {
    			type = new Attribute.Type(a.getType().getUniqueId(), a.getType().getLabel(), a.getType().isConjunctive(), a.getType().isRequired());
    			iAttributeTypes.put(a.getType().getUniqueId(), type);
    			getModel().addAttributeType(type);
    		}
    		attribute = new Attribute(a.getUniqueId(), a.getName(), type);
    		iAttributes.put(a.getUniqueId(), attribute);
    	}
    	return attribute;
    }
    
    protected Course getCourse(CourseOffering co) {
    	Course course = iCourses.get(co.getUniqueId());
    	if (course == null) {
    		course = new Course(co.getUniqueId(), co.getCourseName(), true, true);
    		iCourses.put(co.getUniqueId(), course);
    	}
    	return course;
    }
    
    protected void loadDistributionPreferences(Instructor instructor, DistributionPref dp) {
		if ("BTB_TIME".equals(dp.getDistributionType().getReference()) || "BTB".equals(dp.getDistributionType().getReference())) {
			instructor.setBackToBackPreference(Constants.preference2preferenceLevel(dp.getPrefLevel().getPrefProlog()));
		}
    }
    
    protected void loadTimePreferences(Instructor instructor, TimePref tp) {
		TimePatternModel m = tp.getTimePatternModel();
		boolean out[][] = new boolean [m.getNrDays()][m.getNrTimes()];
        for (int i = 0; i < m.getNrDays(); i++)
               for (int j = 0; j < m.getNrTimes(); j++)
            	   out[i][j] = false;
        for (int i = 0; i < m.getNrDays(); i++)
            for (int j = 0; j < m.getNrTimes(); j++) {
         	   if (out[i][j]) continue;
         	   out[i][j] = true;
         	   if (PreferenceLevel.sNeutral.equals(m.getPreference(i, j))) continue;
         	   int endDay = i, endTime = j;
         	   while (endTime + 1 < m.getNrTimes() && !out[i][endTime+1] && m.getPreference(i, endTime+1).equals(m.getPreference(i, j)))
         		   endTime++;
         	   if (i == 0) {
         		   boolean same = true;
         		   for (int k = i; k + 1 < m.getNrDays(); k++) {
             		   for (int x = j; x <= endTime; x++) {
             			   if (!out[k+1][x] && !m.getPreference(i, x).equals(m.getPreference(k+1, x))) {
             				   same = false; break;
             			   }
             			   if (!same) break;
             		   }
             		   if (!same) break;
         		   }
         		   if (same) endDay = m.getNrDays()-1;
         	   }
         	   while (endDay + 1 < m.getNrDays()) {
         		   boolean same = true;
         		   for (int x = j; x <= endTime; x++)
         			   if (!out[endDay+1][x] && !m.getPreference(i, x).equals(m.getPreference(endDay+1, x))) {
         				   same = false; break;
         			   }
         		   if (!same) break;
         		   endDay++;
         	   }
         	   for (int a = i; a <= endDay;a++)
         		   for (int b = j; b <= endTime;b++)
         			   out[a][b] = true;

         	   int dayCode = 0;
         	   for (int a = i; a <= endDay; a++)
         		   dayCode |= m.getDayCode(a);
         	   TimeLocation time =  new TimeLocation(dayCode, m.getStartSlot(j), m.getStartSlot(endTime) - m.getStartSlot(j) + m.getSlotsPerMtg(), 0, 0.0,
         			   null, "", null, m.getBreakTime());
         	   instructor.addTimePreference(new Preference<TimeLocation>(time, Constants.preference2preferenceLevel(m.getPreference(i, j))));
            }
    }
    
    protected void loadUnavailability(org.hibernate.Session hibSession, DepartmentalInstructor di, Instructor instructor) {
    	if (instructor.getExternalId() != null) {
    		List<StudentClassEnrollment> enrollments = (List<StudentClassEnrollment>)hibSession.createQuery(
    				"from StudentClassEnrollment e where e.student.session.uniqueId = :sessionId and e.student.externalUniqueId = :externalId and e.clazz.cancelled = false"
    				).setLong("sessionId", iSessionId).setString("externalId", instructor.getExternalId()).list();
    		for (StudentClassEnrollment enrollment: enrollments) {
    			org.unitime.timetable.model.Assignment assignment = enrollment.getClazz().getCommittedAssignment();
    			if (assignment != null) {
    				DatePattern datePattern = assignment.getDatePattern();
    				TimeLocation time = new TimeLocation(
    						assignment.getDays().intValue(),
    						assignment.getStartSlot().intValue(),
    						assignment.getSlotPerMtg(),
    						0,0,
    						enrollment.getClazz().getUniqueId(),
    						enrollment.getClazz().getClassLabel(enrollment.getCourseOffering(), true),
    						(datePattern == null ? new BitSet() : datePattern.getPatternBitSet()),
    						assignment.getBreakTime()
    						);
    				instructor.addTimePreference(new Preference<TimeLocation>(time, Constants.sPreferenceLevelProhibited));
    			}
    		}
    		List<ClassInstructor> classInstructors = (List<ClassInstructor>)hibSession.createQuery(
    				"from ClassInstructor ci where ci.instructor.externalUniqueId = :externalId and ci.instructor.department.session.uniqueId = :sessionId and " +
    				"ci.instructor.department.uniqueId != :departmentId and ci.tentative = false and ci.lead = true and ci.classInstructing.cancelled = false"
    				).setLong("sessionId", iSessionId).setString("externalId", instructor.getExternalId()).setLong("departmentId", di.getDepartment().getUniqueId()).list();
    		for (ClassInstructor ci: classInstructors) {
        		org.unitime.timetable.model.Assignment assignment = ci.getClassInstructing().getCommittedAssignment();
        		if (assignment != null) {
        			DatePattern datePattern = assignment.getDatePattern();
    				TimeLocation time = new TimeLocation(
    						assignment.getDays().intValue(),
    						assignment.getStartSlot().intValue(),
    						assignment.getSlotPerMtg(),
    						0,0,
    						ci.getClassInstructing().getUniqueId(),
    						ci.getClassInstructing().getClassLabel(true),
    						(datePattern == null ? new BitSet() : datePattern.getPatternBitSet()),
    						assignment.getBreakTime()
    						);
    				instructor.addTimePreference(new Preference<TimeLocation>(time, Constants.sPreferenceLevelProhibited));
    			}
    		}
    	}
    	for (ClassInstructor ci: di.getClasses()) {
    		if (ci.isTentative() || !ci.isLead() || ci.getClassInstructing().isCancelled() || ci.getClassInstructing().isInstructorAssignmentNeeded()) continue;
    		org.unitime.timetable.model.Assignment assignment = ci.getClassInstructing().getCommittedAssignment();
    		if (assignment != null) {
				instructor.addTimePreference(new Preference<TimeLocation>(assignment.getTimeLocation(), Constants.sPreferenceLevelProhibited));
			}
    	}
    }
    
    protected void loadInstructors(org.hibernate.Session hibSession) throws Exception {
    	List<DepartmentalInstructor> list = (List<DepartmentalInstructor>)hibSession.createQuery(
    			"select distinct i from DepartmentalInstructor i, SolverGroup g inner join g.departments d where " +
    			"g.uniqueId in :solverGroupId and i.department = d and i.teachingPreference.prefProlog != :prohibited and i.maxLoad > 0.0"
    			).setParameterList("solverGroupId", iSolverGroupId).setString("prohibited", PreferenceLevel.sProhibited).list();
    	iProgress.setPhase("Loading instructors...", list.size());
    	for (DepartmentalInstructor i: list) {
    		Instructor instructor = new Instructor(i.getUniqueId(), i.getExternalUniqueId(), i.getName(iInstructorFormat),
    				Constants.preference2preferenceLevel(i.getTeachingPreference().getPrefProlog()), i.getMaxLoad());
    		for (InstructorAttribute a: i.getAttributes())
    			instructor.addAttribute(getAttribute(a));
    		if (!iDepartmentAttribute.isEmpty())
    			instructor.addAttribute(iDepartmentAttribute.get(i.getDepartment().getUniqueId()));
    		for (org.unitime.timetable.model.Preference p: i.getPreferences()) {
    			if (p instanceof InstructorCoursePref) {
    				InstructorCoursePref cp = (InstructorCoursePref)p;
    				instructor.addCoursePreference(new Preference<Course>(getCourse(cp.getCourse()), Constants.preference2preferenceLevel(cp.getPrefLevel().getPrefProlog())));
    			} else if (p instanceof DistributionPref) {
    				loadDistributionPreferences(instructor, (DistributionPref)p);
    			} else if (p instanceof TimePref) {
    				loadTimePreferences(instructor, (TimePref)p);
    			}
    		}
    		loadUnavailability(hibSession, i, instructor);
    		getModel().addInstructor(instructor);
    		iInstructors.put(i.getUniqueId(), instructor);
    		iProgress.incProgress();
    	}
    }
    
    protected Section getSection(Class_ clazz, boolean overlap) {
    	Section section = iSections.get(clazz.getUniqueId());
    	if (section == null) {
    		CourseOffering course = clazz.getSchedulingSubpart().getControllingCourseOffering();
    		String room = null;
    		TimeLocation time = null;
    		org.unitime.timetable.model.Assignment assignment = clazz.getCommittedAssignment();
    		if (assignment != null) {
    			time = assignment.getTimeLocation();
    			for (Location location: assignment.getRooms()) {
    				if (room == null) room = location.getLabel();
    				else room += ", " + location.getLabel();
    			}
    		}
    		section = new Section(clazz.getUniqueId(), clazz.getExternalId(course),
    				clazz.getSchedulingSubpart().getItypeDesc().trim(), clazz.getClassLabel(course),
    				time, room, overlap, !clazz.isInstructorAssignmentNeeded());
    		iSections.put(clazz.getUniqueId(), section);
    	}
    	return section;
    }
    
    protected boolean isToBeIncluded(Class_ clazz) {
    	if (clazz.isCancelled()) return false;
    	if (clazz.isInstructorAssignmentNeeded()) return true;
    	if (iCommonItypes.contains(clazz.getSchedulingSubpart().getItype().getSis_ref())) return true;
    	return false;
    }
    
    protected int nrInstructorsNeeded(Class_ clazz) {
    	int nrChildInstructors = 0;
    	for (Class_ child: clazz.getChildClasses()) {
    		nrChildInstructors += nrInstructorsNeeded(child); 
    	}
    	return Math.max(0, (clazz.isInstructorAssignmentNeeded() ? clazz.effectiveNbrInstructors() : 0) - nrChildInstructors);
    }
    
    protected String toHtml(Class_ clazz) {
    	return "<A href='classDetail.do?cid="+clazz.getUniqueId()+"'>"+clazz.getClassLabel()+"</A>";
    }
    
    protected String toHtml(DepartmentalInstructor instructor) {
    	return "<a href='instructorDetail.do?instructorId=" + instructor.getUniqueId() + "&deptId=" + instructor.getDepartment().getUniqueId() + "'>" + instructor.getName(iInstructorFormat) + "</a>";
    }
    
    protected String toHtml(TeachingAssignment assignment) {
    	return "<a href='instructorDetail.do?instructorId=" + assignment.getInstructor().getInstructorId() + "'>" + assignment.getInstructor().getName() + "</a>";
    }
    
    protected String toHtml(TeachingRequest request) {
    	return "<a href='classDetail.do?cid=" + request.getSections().get(0).getSectionId() + "'>" + request.getCourse().getCourseName() + " " + request.getSections() + "</a>";
    }
    
    protected String toHtml(TeachingRequest.Variable variable) {
    	return "<a href='classDetail.do?cid=" + variable.getRequest().getSections().get(0).getSectionId() + "'>" + variable.getRequest().getCourse().getCourseName() +
    			(variable.getRequest().getNrInstructors() != 1 ? "[" + variable.getInstructorIndex() + "]" : "") + " " + variable.getRequest().getSections() + "</a>";
    }
    
    protected boolean isToBeIgnored(ClassInstructor ci) {
    	if (ci.isTentative()) return false;
    	if (!ci.isLead()) return true;
    	Instructor instructor = iInstructors.get(ci.getInstructor().getUniqueId());
    	if (instructor == null) {
    		iProgress.warn("Instructor " + toHtml(ci.getInstructor()) + " is assigned to " + toHtml(ci.getClassInstructing()) + ", but not allowed for automatic assignment.");
    		return true;
    	}
    	return false;
    }
    
    protected List<DepartmentalInstructor> getInstructors(Class_ clazz) {
    	List<DepartmentalInstructor> instructors = new ArrayList<DepartmentalInstructor>();
    	for (ClassInstructor ci: clazz.getClassInstructors()) {
    		if (!isToBeIgnored(ci)) {
    			if (ci.isTentative()) iHasTentative = true; else iHasCommitted = true;
    			instructors.add(ci.getInstructor());
    		}
    	}
    	for (Class_ child: clazz.getChildClasses()) {
    		if (child.isCancelled() || !child.isInstructorAssignmentNeeded()) continue;
    		for (ClassInstructor ci: child.getClassInstructors())
        		if (!isToBeIgnored(ci)) instructors.remove(ci.getInstructor());
    	}
    	return instructors;
    }
    
    protected void loadRequest(org.hibernate.Session hibSession, Class_ clazz) {
    	int nrInstructors = nrInstructorsNeeded(clazz);
    	if (nrInstructors <= 0) return;
    	Course course = getCourse(clazz.getSchedulingSubpart().getControllingCourseOffering());
    	List<Section> sections = new ArrayList<Section>();
    	sections.add(getSection(clazz, false));
    	float load = clazz.effectiveTeachingLoad();
    	Set<SchedulingSubpart> checked = new HashSet<SchedulingSubpart>();
    	checked.add(clazz.getSchedulingSubpart());
    	for (Class_ parent = clazz.getParentClass(); parent != null; parent = parent.getParentClass()) {
    		checked.add(parent.getSchedulingSubpart());
    		if (isToBeIncluded(parent)) {
    			sections.add(getSection(parent, false));
    			if (parent.isInstructorAssignmentNeeded()) load+= parent.effectiveTeachingLoad();
    		}
    	}
    	for (SchedulingSubpart other: clazz.getSchedulingSubpart().getInstrOfferingConfig().getSchedulingSubparts()) {
    		if (checked.contains(other)) continue;
    		if (iCommonItypes.contains(other.getItype().getSis_ref()) && !other.isInstructorAssignmentNeeded()) {
    			for (Class_ c: other.getClasses())
    				sections.add(getSection(c, other.getClasses().size() > 1));
    		}
    	}
    	List<DepartmentalInstructor> instructors = getInstructors(clazz);
    	if (instructors.size() > nrInstructors)
    		iProgress.warn("There are more instructors are assigned to " + toHtml(clazz) + " than requested.");
		TeachingRequest request = new TeachingRequest(clazz.getUniqueId(), nrInstructors, course, load, sections);
		for (Iterator it = clazz.effectivePreferences(InstructorPref.class).iterator(); it.hasNext(); ) {
			InstructorPref p = (InstructorPref)it.next();
			Instructor instructor = iInstructors.get(p.getInstructor().getUniqueId());
			if (instructor != null) {
				request.addInstructorPreference(new Preference<Instructor>(instructor, Constants.preference2preferenceLevel(p.getPrefLevel().getPrefProlog())));
			}
		}
		for (Iterator it = clazz.effectivePreferences(InstructorAttributePref.class).iterator(); it.hasNext(); ) {
			InstructorAttributePref p = (InstructorAttributePref)it.next();
			request.addAttributePreference(new Preference<Attribute>(getAttribute(p.getAttribute()), Constants.preference2preferenceLevel(p.getPrefLevel().getPrefProlog())));
		}
		if (!iDepartmentAttribute.isEmpty()) {
			request.addAttributePreference(new Preference<Attribute>(iDepartmentAttribute.get(clazz.getControllingDept().getUniqueId()), Constants.sPreferenceLevelRequired));
		}
		getModel().addRequest(request);
		for (int i = 0; i < nrInstructors; i++) {
    		for (Iterator<DepartmentalInstructor> j = instructors.iterator(); j.hasNext(); ) {
    			DepartmentalInstructor di = j.next();
    			Instructor instructor = iInstructors.get(di.getUniqueId());
    			if (instructor != null) {
    				request.getVariable(i).setInitialAssignment(new TeachingAssignment(request.getVariable(i), instructor));
    				j.remove();
    			} else {
    				iProgress.warn("Instructor " + toHtml(di) + " is assigned to " + toHtml(clazz) + ", but not allowed for automatic assignment.");
    				j.remove();
    			}
    		}
    	}
    	if (request.getNrInstructors() > 1) {
    		SameInstructorConstraint diffInstructor = new SameInstructorConstraint(clazz.getUniqueId(), clazz.getClassLabel(), Constants.sPreferenceProhibited);
    		for (TeachingRequest.Variable var: request.getVariables())
    			diffInstructor.addVariable(var);
    		getModel().addConstraint(diffInstructor);
    	}
    }
    
    protected void loadRequests(org.hibernate.Session hibSession) throws Exception {
    	List<Class_> classes = (List<Class_>)hibSession.createQuery(
    			"from Class_ c where c.controllingDept.solverGroup.uniqueId in :solverGroupId and c.cancelled = false and " +
    			"(c.teachingLoad is not null or c.schedulingSubpart.teachingLoad is not null) and " +
    			"((c.nbrInstructors is null and c.schedulingSubpart.nbrInstructors > 0) or c.nbrInstructors > 0)")
    			.setParameterList("solverGroupId", iSolverGroupId).list();
    	Collections.sort(classes, new ClassComparator(ClassComparator.COMPARE_BY_HIERARCHY));
    	iProgress.setPhase("Loading requests...", classes.size());
    	for (Class_ clazz: classes) {
    		iProgress.incProgress();
    		if (!clazz.isInstructorAssignmentNeeded()) continue;
    		loadRequest(hibSession, clazz);
    	}
    }
    
    protected void createAssignment() {
    	iProgress.setPhase("Creating initial assignment...", getModel().variables().size());
    	requests: for (TeachingRequest.Variable request: getModel().variables()) {
    		iProgress.incProgress();
    		TeachingAssignment assignment = request.getInitialAssignment();
    		if (assignment == null) continue;
			if (assignment.getInstructor().getTimePreference(request.getRequest()).isProhibited()) {
				iProgress.warn("Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment) + ": instructor is not available.");
				continue;
			}
			if (assignment.getInstructor().getCoursePreference(request.getCourse()).isProhibited()) {
				iProgress.warn("Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment) + ": course " + request.getCourse().getCourseName() + " is prohibited.");
				continue;
			}
			if (request.getRequest().getInstructorPreference(assignment.getInstructor()).isProhibited()) {
				iProgress.warn("Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment) + ": instructor " + assignment.getInstructor().getName() + " is prohibited.");
				continue;
			}
			for (Attribute.Type type: getModel().getAttributeTypes()) {
				int pref = request.getRequest().getAttributePreference(assignment.getInstructor(), type);
				if (Constants.sPreferenceProhibited.equals(Constants.preferenceLevel2preference(pref))) {
					iProgress.warn("Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment) + ": probibited by attribute type " + type.getTypeName() + ".");
					continue requests;
				}
			}
			if (!assignment.getInstructor().canTeach(request.getRequest()) || request.getRequest().getAttributePreference(assignment.getInstructor()).isProhibited()) {
				iProgress.warn("Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment) + ": assignment not valid.");
				continue;
			}
			getModel().weaken(getAssignment(), assignment);
			Map<Constraint<TeachingRequest.Variable, TeachingAssignment>, Set<TeachingAssignment>> conflictConstraints = getModel().conflictConstraints(getAssignment(), assignment);
        	if (conflictConstraints.isEmpty()) {
        		getAssignment().assign(0, assignment);
	        } else {
                String warn = "Unable to assign " + toHtml(request) +" &larr; " + toHtml(assignment);
                warn += "<br>&nbsp;&nbsp;Reason:";
                for (Constraint<TeachingRequest.Variable, TeachingAssignment> c: conflictConstraints.keySet()) {
                	Set<TeachingAssignment> vals = conflictConstraints.get(c);
                	for (TeachingAssignment v: vals) {
                        warn += "<br>&nbsp;&nbsp;&nbsp;&nbsp;" + toHtml(v.variable()) + " = " + toHtml(v);
            	    }
                    warn += "<br>&nbsp;&nbsp;&nbsp;&nbsp;    in constraint " + c;
                    iProgress.warn(warn);
    	        }
	        }
    	}
    }
}