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
package org.unitime.timetable.solver.studentsct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.cpsolver.coursett.model.TimeLocation;
import org.cpsolver.ifs.assignment.Assignment;
import org.cpsolver.studentsct.StudentSectioningModel;
import org.cpsolver.studentsct.model.Config;
import org.cpsolver.studentsct.model.Course;
import org.cpsolver.studentsct.model.CourseRequest;
import org.cpsolver.studentsct.model.Enrollment;
import org.cpsolver.studentsct.model.FreeTimeRequest;
import org.cpsolver.studentsct.model.Offering;
import org.cpsolver.studentsct.model.Request;
import org.cpsolver.studentsct.model.SctAssignment;
import org.cpsolver.studentsct.model.Section;
import org.cpsolver.studentsct.model.Student;
import org.unitime.localization.impl.Localization;
import org.unitime.timetable.gwt.resources.StudentSectioningMessages;
import org.unitime.timetable.gwt.server.DayCode;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface;
import org.unitime.timetable.gwt.shared.CourseRequestInterface;
import org.unitime.timetable.gwt.shared.SectioningException;
import org.unitime.timetable.onlinesectioning.OnlineSectioningHelper;
import org.unitime.timetable.onlinesectioning.OnlineSectioningServer;
import org.unitime.timetable.onlinesectioning.basic.GetAssignment;
import org.unitime.timetable.onlinesectioning.custom.StudentEnrollmentProvider.EnrollmentFailure;
import org.unitime.timetable.onlinesectioning.model.XCourse;
import org.unitime.timetable.onlinesectioning.model.XOffering;
import org.unitime.timetable.onlinesectioning.updates.EnrollStudent;

public class BatchEnrollStudent extends EnrollStudent {
	private static final long serialVersionUID = 1L;
	private static StudentSectioningMessages MSG = Localization.create(StudentSectioningMessages.class);
	private static AtomicLong sLastGeneratedId = new AtomicLong(-1l);
	public static final String sRequestsChangedStatus = "Modified";

	@Override
	public ClassAssignmentInterface execute(OnlineSectioningServer server, final OnlineSectioningHelper helper) {
		if (getRequest().getStudentId() == null)
			throw new SectioningException(MSG.exceptionNoStudent());
		
		StudentSolver solver = (StudentSolver) server;
		StudentSectioningModel model = (StudentSectioningModel) solver.currentSolution().getModel();
		Assignment<Request, Enrollment> assignment = solver.currentSolution().getAssignment();
		Student student = null;
		for (Student s: model.getStudents()) {
			if (s.getId() == getRequest().getStudentId()) { student = s; break; }
		}
		
		if (student == null)
			throw new SectioningException(MSG.exceptionBadStudentId());
		
		List<EnrollmentFailure> failures = new ArrayList<EnrollmentFailure>();
		
		if (solver.getConfig().getPropertyBoolean("Interactive.UpdateCourseRequests", true)) {
			List<Request> remaining = new ArrayList<Request>(student.getRequests());
			int priority = 0;
			Long ts = new Date().getTime();
			boolean changed = false;
			for (CourseRequestInterface.Request r: getRequest().getCourses()) {
				if (r.hasRequestedFreeTime() && r.hasRequestedCourse() && server.getCourse(r.getRequestedCourse()) != null)
					r.getRequestedFreeTime().clear();
				if (r.hasRequestedFreeTime()) {
					for (CourseRequestInterface.FreeTime ft: r.getRequestedFreeTime()) {
						TimeLocation time = new TimeLocation(
								DayCode.toInt(DayCode.toDayCodes(ft.getDays())),
		                        ft.getStart(),
		                        ft.getLength(),
		                        0, 0, -1l, "", server.getAcademicSession().getFreeTimePattern(), 0);
						
						FreeTimeRequest freeTimeRequest = null;
						for (Iterator<Request> i = remaining.iterator(); i.hasNext(); ) {
							Request adept = i.next();
							if (adept instanceof FreeTimeRequest && !adept.isAlternative()) {
								FreeTimeRequest f = (FreeTimeRequest) adept;
								if (f.getTime().equals(time)) { freeTimeRequest = f; i.remove(); break; }
							}
						}
						
						if (freeTimeRequest == null) {
							freeTimeRequest = new FreeTimeRequest(sLastGeneratedId.getAndDecrement(), priority, false, student, time);
							model.addVariable(freeTimeRequest);
							changed = true;
						} else {
							if (freeTimeRequest.getPriority() != priority) {
								freeTimeRequest.setPriority(priority);
								changed = true;
							}
						}
					}
				} else {
					List<Course> courses = new ArrayList<Course>();
					if (r.hasRequestedCourse()) {
						Course c = getCourse(model, r.getRequestedCourse());
						if (c != null) courses.add(c);
					}
					if (r.hasFirstAlternative()) {
						Course c = getCourse(model, r.getFirstAlternative());
						if (c != null) courses.add(c);
					}
					if (r.hasSecondAlternative()) {
						Course c = getCourse(model, r.getSecondAlternative());
						if (c != null) courses.add(c);
					}
					if (courses.isEmpty()) continue;
					
					CourseRequest courseRequest = null;
					for (Iterator<Request> i = remaining.iterator(); i.hasNext(); ) {
						Request adept = i.next();
						if (adept instanceof CourseRequest && !adept.isAlternative()) {
							CourseRequest cr = (CourseRequest) adept;
							if (cr.getCourses().equals(courses)) {
								courseRequest = cr; i.remove(); break;
							}
						}
					}
					
					if (courseRequest == null) {
						courseRequest = new CourseRequest(
								sLastGeneratedId.getAndDecrement(),
								priority,
								false,
		                        student,
		                        courses,
		                        r.isWaitList(), 
		                        ts);
						model.addVariable(courseRequest);
						changed = true;
					} else {
						if (courseRequest.getPriority() != priority) {
							courseRequest.setPriority(priority);
							changed = true;
						}
						if (courseRequest.isWaitlist() != r.isWaitList()) {
							courseRequest.setWaitlist(r.isWaitList());
							changed = true;
						}
					}
				}
				priority++;
			}
			
			for (CourseRequestInterface.Request r: getRequest().getAlternatives()) {
				if (r.hasRequestedFreeTime() && r.hasRequestedCourse() && server.getCourse(r.getRequestedCourse()) != null)
					r.getRequestedFreeTime().clear();
				if (r.hasRequestedFreeTime()) {
					for (CourseRequestInterface.FreeTime ft: r.getRequestedFreeTime()) {
						TimeLocation time = new TimeLocation(
								DayCode.toInt(DayCode.toDayCodes(ft.getDays())),
		                        ft.getStart(),
		                        ft.getLength(),
		                        0, 0, -1l, "", server.getAcademicSession().getFreeTimePattern(), 0);
						
						FreeTimeRequest freeTimeRequest = null;
						for (Iterator<Request> i = remaining.iterator(); i.hasNext(); ) {
							Request adept = i.next();
							if (adept instanceof FreeTimeRequest && adept.isAlternative()) {
								FreeTimeRequest f = (FreeTimeRequest) adept;
								if (f.getTime().equals(time)) { freeTimeRequest = f; i.remove(); break; }
							}
						}
						
						if (freeTimeRequest == null) {
							freeTimeRequest = new FreeTimeRequest(sLastGeneratedId.getAndDecrement(), priority, true, student, time);
							model.addVariable(freeTimeRequest);
							changed = true;
						} else {
							if (freeTimeRequest.getPriority() != priority) {
								freeTimeRequest.setPriority(priority);
								changed = true;
							}
						}
					}
				} else {
					List<Course> courses = new ArrayList<Course>();
					if (r.hasRequestedCourse()) {
						Course c = getCourse(model, r.getRequestedCourse());
						if (c != null) courses.add(c);
					}
					if (r.hasFirstAlternative()) {
						Course c = getCourse(model, r.getFirstAlternative());
						if (c != null) courses.add(c);
					}
					if (r.hasSecondAlternative()) {
						Course c = getCourse(model, r.getSecondAlternative());
						if (c != null) courses.add(c);
					}
					if (courses.isEmpty()) continue;
					
					CourseRequest courseRequest = null;
					for (Iterator<Request> i = remaining.iterator(); i.hasNext(); ) {
						Request adept = i.next();
						if (adept instanceof CourseRequest && adept.isAlternative()) {
							CourseRequest cr = (CourseRequest) adept;
							if (cr.getCourses().equals(courses)) {
								courseRequest = cr; i.remove(); break;
							}
						}
					}
					
					if (courseRequest == null) {
						courseRequest = new CourseRequest(
								sLastGeneratedId.getAndDecrement(),
								priority,
								true,
		                        student,
		                        courses,
		                        r.isWaitList(), 
		                        ts);
						model.addVariable(courseRequest);
						changed = true;
					} else {
						if (courseRequest.getPriority() != priority) {
							courseRequest.setPriority(priority);
							changed = true;
						}
					}
				}
				priority++;
			}
			
			for (Request request: remaining) {
				Enrollment enrollment = assignment.getValue(request);
				if (enrollment != null)
					assignment.unassign(0l, request);
				student.getRequests().remove(request);
				model.removeVariable(request);
				changed = true;
			}
			
			if (changed) {
				Collections.sort(student.getRequests());
				student.setStatus(sRequestsChangedStatus);
			}
		}
		
		Map<CourseRequest, List<Section>> enrollments = new HashMap<CourseRequest, List<Section>>();
		assignments: for (ClassAssignmentInterface.ClassAssignment a: getAssignment()) {
			if (a != null && a.getCourseId() != null && a.getClassId() != null) {
				CourseRequest request = null;
				Course course = null;
				requests: for (Request r: student.getRequests()) {
					if (r instanceof CourseRequest) {
						for (Course c: ((CourseRequest)r).getCourses()) {
							if (c.getId() == a.getCourseId()) {
								course = c;
								request = (CourseRequest)r;
								break requests;
							}
						}
					}
				}
				if (request == null) {
					XCourse c = server.getCourse(a.getCourseId());
					XOffering offering = server.getOffering(c.getOfferingId());
					failures.add(new EnrollmentFailure(c, offering.getSection(a.getClassId()), "Adding courses is not supported at the moment.", false));
					continue assignments;
				}
				Section section = course.getOffering().getSection(a.getClassId());
				List<Section> sections = enrollments.get(request);
				if (sections == null) {
					sections = new ArrayList<Section>();
					enrollments.put(request, sections);
				}
				sections.add(section);
			}
		}
		
		for (Request request: student.getRequests()) {
			Enrollment enrollment = assignment.getValue(request);
			if (enrollment != null)
				assignment.unassign(0l, request);
		}
		for (Request request: student.getRequests()) {
			if (request instanceof CourseRequest) {
				CourseRequest cr = (CourseRequest)request;
				List<Section> sections = enrollments.get(cr);
				if (sections != null) {
					Section section = sections.get(0);
					int pririty = 0;
					Config config = section.getSubpart().getConfig();
					for (int i = 0; i < cr.getCourses().size(); i++) {
						if (cr.getCourses().get(i).getOffering().equals(config.getOffering())) {
							pririty = i;
							break;
						}
					}
					assignment.assign(0l, new Enrollment(cr, pririty, config, new HashSet<SctAssignment>(sections), assignment));
				}
			} else {
				FreeTimeRequest ft = (FreeTimeRequest)request;
				Enrollment enrollment = ft.createEnrollment();
				if (!model.inConflict(assignment, enrollment))
					assignment.assign(0l, enrollment);
			}
		}
		
		return server.execute(server.createAction(GetAssignment.class).forStudent(getStudentId()).withMessages(failures), helper.getUser());
	}
	
	protected Course getCourse(StudentSectioningModel model, String courseName){
		for (Offering offering: model.getOfferings())
			for (Course course: offering.getCourses())
				if (course.getName().equalsIgnoreCase(courseName)) return course;
		return null;
	}
}
