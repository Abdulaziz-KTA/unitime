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
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.locks.Lock;

import org.cpsolver.coursett.Constants;
import org.cpsolver.coursett.model.TimeLocation;
import org.cpsolver.ifs.assignment.Assignment;
import org.cpsolver.ifs.criteria.Criterion;
import org.cpsolver.ifs.solver.Solver;
import org.cpsolver.ifs.util.DataProperties;
import org.cpsolver.ifs.util.ProblemLoader;
import org.cpsolver.ifs.util.ProblemSaver;
import org.cpsolver.ifs.util.Progress;
import org.cpsolver.instructor.model.Attribute;
import org.cpsolver.instructor.model.Course;
import org.cpsolver.instructor.model.Instructor;
import org.cpsolver.instructor.model.InstructorSchedulingModel;
import org.cpsolver.instructor.model.Preference;
import org.cpsolver.instructor.model.Section;
import org.cpsolver.instructor.model.TeachingAssignment;
import org.cpsolver.instructor.model.TeachingRequest;
import org.dom4j.Document;
import org.unitime.localization.impl.Localization;
import org.unitime.timetable.gwt.resources.GwtConstants;
import org.unitime.timetable.gwt.shared.InstructorInterface.AttributeInterface;
import org.unitime.timetable.gwt.shared.InstructorInterface.AttributeTypeInterface;
import org.unitime.timetable.gwt.shared.InstructorInterface.CourseInfo;
import org.unitime.timetable.gwt.shared.InstructorInterface.InstructorInfo;
import org.unitime.timetable.gwt.shared.InstructorInterface.PreferenceInfo;
import org.unitime.timetable.gwt.shared.InstructorInterface.SectionInfo;
import org.unitime.timetable.gwt.shared.InstructorInterface.TeachingRequestInfo;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.PreferenceLevel;
import org.unitime.timetable.model.SolverParameterGroup.SolverType;
import org.unitime.timetable.model.dao.CourseOfferingDAO;
import org.unitime.timetable.solver.AbstractSolver;
import org.unitime.timetable.solver.SolverDisposeListener;

/**
 * @author Tomas Muller
 */
public class InstructorSchedulingSolver extends AbstractSolver<TeachingRequest, TeachingAssignment, InstructorSchedulingModel> implements InstructorSchedulingProxy {
	protected static GwtConstants CONSTANTS = Localization.create(GwtConstants.class); 
	
	public InstructorSchedulingSolver(DataProperties properties, SolverDisposeListener disposeListener) {
		super(properties, disposeListener);
	}

	@Override
	public SolverType getType() {
		return SolverType.INSTRUCTOR;
	}

	@Override
	protected ProblemSaver<TeachingRequest, TeachingAssignment, InstructorSchedulingModel> getDatabaseSaver( Solver<TeachingRequest, TeachingAssignment> solver) {
		return new InstructorSchedulingDatabaseSaver(solver);
	}

	@Override
	protected ProblemLoader<TeachingRequest, TeachingAssignment, InstructorSchedulingModel> getDatabaseLoader(InstructorSchedulingModel model, Assignment<TeachingRequest, TeachingAssignment> assignment) {
		return new InstructorSchedulingDatabaseLoader(model, assignment);
	}

	@Override
	protected InstructorSchedulingModel createModel(DataProperties properties) {
		return new InstructorSchedulingModel(properties);
	}

	@Override
	protected Document createCurrentSolutionBackup(boolean anonymize, boolean idconv) {
		if (anonymize) {
            getProperties().setProperty("Xml.Anonymize", "true");
            getProperties().setProperty("Xml.ShowNames", "false");
            getProperties().setProperty("Xml.ConvertIds", idconv ? "true" : "false");
            getProperties().setProperty("Xml.SaveInitial", "false");
            getProperties().setProperty("Xml.SaveBest", "false");
            getProperties().setProperty("Xml.SaveSolution", "true");
		} else {
            getProperties().setProperty("Xml.Anonymize", "false");
            getProperties().setProperty("Xml.ShowNames", "true");
            getProperties().setProperty("Xml.ConvertIds", "false");
            getProperties().setProperty("Xml.SaveInitial", "true");
            getProperties().setProperty("Xml.SaveBest", "true");
            getProperties().setProperty("Xml.SaveSolution", "true");
		}
		InstructorSchedulingModel model = (InstructorSchedulingModel)currentSolution().getModel();
		Document document = model.save(currentSolution().getAssignment());
		if (document == null) return null;
        if (!anonymize) {
            Progress p = Progress.getInstance(model);
            if (p != null)
            	Progress.getInstance(this).save(document.getRootElement());
        }
        return document;
	}

	@Override
	protected void restureCurrentSolutionFromBackup(Document document) {
		InstructorSchedulingModel model = (InstructorSchedulingModel)currentSolution().getModel();
		model.load(document, currentSolution().getAssignment());
        Progress p = Progress.getInstance(model);
        if (p != null) {
            p.load(document.getRootElement(), true);
            p.message(Progress.MSGLEVEL_STAGE, "Restoring from backup ...");
        }
	}
	
	protected TeachingRequestInfo toInfo(TeachingRequest request, TeachingAssignment assignment) {
		TeachingRequestInfo info = new TeachingRequestInfo();
		info.setRequestId(request.getRequestId());
		info.setInstructorIndex(request.getInstructorIndex());
		info.setLoad(request.getLoad());
		for (Preference<Attribute> p: request.getAttributePreferences())
			info.addAttributePreference(new PreferenceInfo(p.getTarget().getAttributeId(), p.getTarget().getAttributeName(), Constants.preferenceLevel2preference(p.getPreference())));
		for (Preference<Instructor> p: request.getInstructorPreferences())
			info.addInstructorPreference(new PreferenceInfo(p.getTarget().getInstructorId(), p.getTarget().getName(), Constants.preferenceLevel2preference(p.getPreference())));
		CourseInfo course = new CourseInfo();
		course.setCourseId(request.getCourse().getCourseId());
		course.setCourseName(request.getCourse().getCourseName());
		info.setCourse(course);
		boolean useAmPm = getProperties().getPropertyBoolean("General.UseAmPm", true);
		for (Section section: request.getSections()) {
			SectionInfo si = new SectionInfo();
			si.setSectionId(section.getSectionId());
			si.setExternalId(section.getExternalId());
			si.setSectionName(section.getSectionName());
			si.setSectionType(section.getSectionType());
			si.setCommon(section.isCommon());
			si.setTime(section.hasTime() ? section.getTimeName(useAmPm) : null);
			si.setDate(section.hasTime() ? section.getTime().getDatePatternName() : null);
			si.setRoom(section.getRoom());
			info.addSection(si);
		}
		if (assignment != null) {
			InstructorInfo instructor = new InstructorInfo();
			instructor.setInstructorId(assignment.getInstructor().getInstructorId());
			instructor.setInstructorName(assignment.getInstructor().getName());
			instructor.setExternalId(assignment.getInstructor().getExternalId());
			instructor.setAssignedLoad(assignment.getInstructor().getContext(currentSolution().getAssignment()).getLoad());
			instructor.setMaxLoad(assignment.getInstructor().getMaxLoad());
			for (Preference<Course> p: assignment.getInstructor().getCoursePreferences())
				instructor.addCoursePreference(new PreferenceInfo(p.getTarget().getCourseId(), p.getTarget().getCourseName(), Constants.preferenceLevel2preference(p.getPreference())));
			int[][] slot2pref = new int[Constants.NR_DAYS * Constants.SLOTS_PER_DAY][];
			for (int i = 0; i < slot2pref.length; i++)
				slot2pref[i] = new int[] {0, 0, 0};
			for (Preference<TimeLocation> p: assignment.getInstructor().getTimePreferences()) {
				PreferenceInfo pi = new PreferenceInfo(new Long(p.getTarget().hashCode()), p.getTarget().getLongName(useAmPm), Constants.preferenceLevel2preference(p.getPreference()));
				pi.setComparable(String.format("%03d:%05d", p.getTarget().getDayCode(), p.getTarget().getStartSlot()));
				instructor.addTimePreference(pi);
				for (Enumeration<Integer> i = p.getTarget().getSlots(); i.hasMoreElements(); ) {
					int slot = i.nextElement();
					slot2pref[slot][0] = Math.min(slot2pref[slot][0], p.getPreference());
					slot2pref[slot][1] = Math.max(slot2pref[slot][1], p.getPreference());
					if (p.isProhibited() && p.getTarget().getTimePatternId() != null) slot2pref[slot][2] = 1;
				}
			}
			StringBuffer pattern = new StringBuffer(slot2pref.length);
			for (int i = 0; i < slot2pref.length; i++) {
				int min = slot2pref[i][0];
				int max = slot2pref[i][1];
				int pref = (max > -min ? max : -min > max ? min : max);
				if (slot2pref[i][2] == 1)
					pattern.append(PreferenceLevel.prolog2char(PreferenceLevel.sNotAvailable));
				else
					pattern.append(PreferenceLevel.prolog2char(Constants.preferenceLevel2preference(pref)));
			}
			instructor.setAvailability(pattern.toString());
			for (Attribute a: assignment.getInstructor().getAttributes()) {
				AttributeInterface attribute = new AttributeInterface();
				attribute.setId(a.getAttributeId());
				attribute.setName(a.getAttributeName());
				AttributeTypeInterface type = new AttributeTypeInterface();
				type.setId(a.getType().getTypeId());
				type.setLabel(a.getType().getTypeName());
				type.setConjunctive(a.getType().isConjunctive());
				type.setRequired(a.getType().isRequired());
				attribute.setType(type);
				instructor.addAttribute(attribute);
			}
			if (assignment.getInstructor().getPreference() != 0)
				instructor.setTeachingPreference(Constants.preferenceLevel2preference(assignment.getInstructor().getPreference()));
			if (assignment.getInstructor().getBackToBackPreference() != 0)
				instructor.addDistributionPreference(new PreferenceInfo(1l, CONSTANTS.instructorBackToBack(), Constants.preferenceLevel2preference(assignment.getInstructor().getBackToBackPreference())));
			for (Criterion<TeachingRequest, TeachingAssignment> c: request.getModel().getCriteria()) {
				double value = c.getValue(currentSolution().getAssignment(), assignment, null);
				if (value != 0) instructor.setValue(c.getName(), value);
			}
			info.setInstructor(instructor);
		}
		return info;
	}

	@Override
	public List<TeachingRequestInfo> getTeachingRequests(Long subjectAreaId, boolean assigned) {
        Lock lock = currentSolution().getLock().readLock();
        lock.lock();
        try {
            List<TeachingRequestInfo> ret = new ArrayList<TeachingRequestInfo>();
            for (TeachingRequest request: currentSolution().getModel().variables()) {
            	if (subjectAreaId != null) {
            		CourseOffering course = CourseOfferingDAO.getInstance().get(request.getCourse().getCourseId());
            		if (course == null || !subjectAreaId.equals(course.getSubjectArea().getUniqueId())) continue;
            	}
            	TeachingAssignment placement = currentSolution().getAssignment().getValue(request);
            	if ((assigned && placement != null) || (!assigned && placement == null))
                    ret.add(toInfo(request, placement));
            }
            return ret;
        } finally {
        	lock.unlock();
        }
	}
}
