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
package org.unitime.timetable.gwt.shared;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.ErrorMessage;

import com.google.gwt.user.client.rpc.IsSerializable;

/**
 * @author Tomas Muller
 */
public class SpecialRegistrationInterface implements IsSerializable, Serializable {
	private static final long serialVersionUID = 1L;
	
	public static class SpecialRegistrationEligibilityRequest implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private Long iSessionId;
		private Long iStudentId;
		private Collection<ClassAssignmentInterface.ClassAssignment> iClassAssignments;
		private ArrayList<ErrorMessage> iErrors = null;
		
		public SpecialRegistrationEligibilityRequest() {}
		public SpecialRegistrationEligibilityRequest(Long sessionId, Long studentId, Collection<ClassAssignmentInterface.ClassAssignment> assignments, Collection<ErrorMessage> errors) {
			iClassAssignments = assignments;
			iStudentId = studentId;
			iSessionId = sessionId;
			if (errors != null)
				iErrors = new ArrayList<ErrorMessage>(errors);
		}
		
		public Long getSessionId() { return iSessionId; }
		public void setSessionId(Long sessionId) { iSessionId = sessionId; }
		public Long getStudentId() { return iStudentId; }
		public void setStudentId(Long studentId) { iStudentId = studentId; }
		public Collection<ClassAssignmentInterface.ClassAssignment> getClassAssignments() { return iClassAssignments; }
		public void setClassAssignments(Collection<ClassAssignmentInterface.ClassAssignment> assignments) { iClassAssignments = assignments; }
		public void addError(ErrorMessage error) {
			if (iErrors == null) iErrors = new ArrayList<ErrorMessage>();
			iErrors.add(error);
		}
		public boolean hasErrors() {
			return iErrors != null && !iErrors.isEmpty();
		}
		public ArrayList<ErrorMessage> getErrors() { return iErrors; }		
	}
	
	public static class SpecialRegistrationEligibilityResponse implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private String iMessage;
		private boolean iCanSubmit;
		
		public SpecialRegistrationEligibilityResponse() {}
		public SpecialRegistrationEligibilityResponse(boolean canSubmit, String message) {
			iCanSubmit = canSubmit; iMessage = message;
		}
	
		public boolean isCanSubmit() { return iCanSubmit; }
		public void setCanSubmit(boolean canSubmit) { iCanSubmit = canSubmit; }
		
		public boolean hasMessage() { return iMessage != null && !iMessage.isEmpty(); }
		public String getMessage() { return iMessage; }
		public void setMessage(String message) { iMessage = message; }
	}
	
	public static class RetrieveSpecialRegistrationRequest implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private Long iSessionId;
		private Long iStudentId;
		private String iRequestId;
		
		public RetrieveSpecialRegistrationRequest() {}
		public RetrieveSpecialRegistrationRequest(Long sessionId, Long studentId, String requestId) {
			iRequestId = requestId;
			iStudentId = studentId;
			iSessionId = sessionId;
		}
		
		public Long getSessionId() { return iSessionId; }
		public void setSessionId(Long sessionId) { iSessionId = sessionId; }
		public Long getStudentId() { return iStudentId; }
		public void setStudentId(Long studentId) { iStudentId = studentId; }
		public String getRequestId() { return iRequestId; }
		public void setRequestId(String requestId) { iRequestId = requestId; }
	}
	
	public static class RetrieveSpecialRegistrationResponse implements IsSerializable, Serializable, Comparable<RetrieveSpecialRegistrationResponse> {
		private static final long serialVersionUID = 1L;
		private ClassAssignmentInterface iClassAssignment;
		private boolean iCanSubmit;
		private boolean iCanEnroll;
		private Date iSubmitDate;
		private String iRequestId;
		private String iDescription;
		
		public RetrieveSpecialRegistrationResponse() {}
		
		public boolean hasClassAssignments() { return iClassAssignment != null; }
		public ClassAssignmentInterface getClassAssignments() { return iClassAssignment; }
		public void setClassAssignments(ClassAssignmentInterface assignments) { iClassAssignment = assignments; }

		public boolean isCanSubmit() { return iCanSubmit; }
		public void setCanSubmit(boolean canSubmit) { iCanSubmit = canSubmit; }
		
		public boolean isCanEnroll() { return iCanEnroll; }
		public void setCanEnroll(boolean canEnroll) { iCanEnroll = canEnroll; }
		
		public Date getSubmitDate() { return iSubmitDate; }
		public void setSubmitDate(Date date) { iSubmitDate = date; }
		
		public String getRequestId() { return iRequestId; }
		public void setRequestId(String requestId) { iRequestId = requestId; }
		
		public String getDescription() { return iDescription; }
		public void setDescription(String description) { iDescription = description; }

		@Override
		public int compareTo(RetrieveSpecialRegistrationResponse o) {
			int cmp = getSubmitDate().compareTo(o.getSubmitDate());
			if (cmp != 0) return -cmp;
			return getRequestId().compareTo(o.getRequestId());
		}
		
		
	}
	
	public static class SubmitSpecialRegistrationRequest implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private Long iSessionId;
		private Long iStudentId;
		private String iRequestId;
		private CourseRequestInterface iCourses;
		private Collection<ClassAssignmentInterface.ClassAssignment> iClassAssignments;
		private ArrayList<ErrorMessage> iErrors = null;
		
		public SubmitSpecialRegistrationRequest() {}
		public SubmitSpecialRegistrationRequest(Long sessionId, Long studentId, String requestId, CourseRequestInterface courses, Collection<ClassAssignmentInterface.ClassAssignment> assignments, Collection<ErrorMessage> errors) {
			iRequestId = requestId;
			iStudentId = studentId;
			iSessionId = sessionId;
			iCourses = courses;
			iClassAssignments = assignments;
			if (errors != null)
				iErrors = new ArrayList<ErrorMessage>(errors);
		}
		
		public Collection<ClassAssignmentInterface.ClassAssignment> getClassAssignments() { return iClassAssignments; }
		public void setClassAssignments(Collection<ClassAssignmentInterface.ClassAssignment> assignments) { iClassAssignments = assignments; }
		public CourseRequestInterface getCourses() { return iCourses; }
		public void setCourses(CourseRequestInterface courses) { iCourses = courses; }
		public Long getSessionId() { return iSessionId; }
		public void setSessionId(Long sessionId) { iSessionId = sessionId; }
		public Long getStudentId() { return iStudentId; }
		public void setStudentId(Long studentId) { iStudentId = studentId; }
		public String getRequestId() { return iRequestId; }
		public void setRequestId(String requestId) { iRequestId = requestId; }
		public void addError(ErrorMessage error) {
			if (iErrors == null) iErrors = new ArrayList<ErrorMessage>();
			iErrors.add(error);
		}
		public boolean hasErrors() {
			return iErrors != null && !iErrors.isEmpty();
		}
		public ArrayList<ErrorMessage> getErrors() { return iErrors; }		
	}
	
	public static class SubmitSpecialRegistrationResponse implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private String iRequestId;
		private String iMessage;
		private boolean iCanSubmit;
		private boolean iCanEnroll;
		private boolean iSuccess;
		
		public SubmitSpecialRegistrationResponse() {}
		
		public String getRequestId() { return iRequestId; }
		public void setRequestId(String requestId) { iRequestId = requestId; }
		
		public boolean hasMessage() { return iMessage != null && !iMessage.isEmpty(); }
		public String getMessage() { return iMessage; }
		public void setMessage(String message) { iMessage = message; }
		
		public boolean isSuccess() { return iSuccess; }
		public boolean isFailure() { return !iSuccess; }
		public void setSuccess(boolean success) { iSuccess = success; }
		
		public boolean isCanSubmit() { return iCanSubmit; }
		public void setCanSubmit(boolean canSubmit) { iCanSubmit = canSubmit; }
		
		public boolean isCanEnroll() { return iCanEnroll; }
		public void setCanEnroll(boolean canEnroll) { iCanEnroll = canEnroll; }
	}
	
	public static class RetrieveAllSpecialRegistrationsRequest implements IsSerializable, Serializable {
		private static final long serialVersionUID = 1L;
		private Long iSessionId;
		private Long iStudentId;
		
		public RetrieveAllSpecialRegistrationsRequest() {}
		public RetrieveAllSpecialRegistrationsRequest(Long sessionId, Long studentId) {
			iStudentId = studentId;
			iSessionId = sessionId;
		}
		
		public Long getSessionId() { return iSessionId; }
		public void setSessionId(Long sessionId) { iSessionId = sessionId; }
		public Long getStudentId() { return iStudentId; }
		public void setStudentId(Long studentId) { iStudentId = studentId; }
	}
}
