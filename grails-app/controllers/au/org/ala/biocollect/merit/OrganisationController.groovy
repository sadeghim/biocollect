package au.org.ala.biocollect.merit

import grails.converters.JSON

/**
 * Processes requests relating to organisations in field capture.
 */
class OrganisationController {

    def organisationService, searchService, documentService, userService, roleService
    def citizenScienceOrgId = null

    def list() {
        if (params.createCitizenScienceProject as boolean) { // came from CS Hub page
            if (citizenScienceOrgId == null) {
                def orgName = grailsApplication.config.citizenScienceOrgName?:"ALA"
                citizenScienceOrgId = organisationService.getByName(orgName)?.organisationId
            }
            // this session attribute indicates user's desire to create a citizen science project
            // this attribute is cleared on any project indez/edit/create action
            session.setAttribute('citizenScienceOrgId', citizenScienceOrgId)
        }
        def organisations = organisationService.list()
        def user = userService.getUser()
        def userOrgIds = user? userService.getOrganisationIdsForUserId(user.userId): []
        [organisations:organisations.list?:[],
         user:user,
         userOrgIds: userOrgIds,
         citizenScienceOrgId: session.getAttribute('citizenScienceOrgId')?:''
        ]
    }

    def index(String id) {
        def organisation = organisationService.get(id, 'all')

        if (!organisation || organisation.error) {
            organisationNotFound(id, organisation)
        }
        else {
            def roles = roleService.getRoles()
            // Get dashboard information for the response.
            def dashboard = searchService.dashboardReport([fq: 'organisationFacet:' + organisation.name])
            def members = organisationService.getMembersOfOrganisation(id)
            def user = userService.getUser()
            def userId = user?.userId

            def orgRole = members.find{it.userId == userId}

            [organisation: organisation,
             dashboard: dashboard,
             roles:roles,
             user:user,
             isAdmin:orgRole?.role == RoleService.PROJECT_ADMIN_ROLE,
             isGrantManager:orgRole?.role == RoleService.GRANT_MANAGER_ROLE,
             content:content(organisation)]
        }
    }

    protected Map content(organisation) {

        def user = userService.getUser()
        def members = organisationService.getMembersOfOrganisation(organisation.organisationId)
        def orgRole = members.find { it.userId == user?.userId } ?: [:]
        def hasAdminAccess = userService.userIsAlaOrFcAdmin() || orgRole.role == RoleService.PROJECT_ADMIN_ROLE

        def hasViewAccess = hasAdminAccess || userService.userHasReadOnlyAccess() || orgRole.role == RoleService.PROJECT_EDITOR_ROLE
        def dashboardReports = [[name:'dashboard', label:'Activity Outputs']]
        def includeProjectList = organisation.projects?.size() > 0

        [about    : [label: 'About', visible: true, stopBinding: false, type:'tab', default:true, includeProjectList:includeProjectList],
         sites    : [label: 'Sites', visible: hasViewAccess, stopBinding:true, type: 'tab', template:'/shared/sites', projectCount:organisation.projects?.size()?:0],
         dashboard: [label: 'Dashboard', visible: hasViewAccess, stopBinding:true, type: 'tab', template:'/shared/dashboard', reports:dashboardReports],
         admin    : [label: 'Admin', visible: hasAdminAccess, type: 'tab']]
    }

    def create() {
        [organisation:[:]]
    }

    def edit(String id) {
        def organisation = organisationService.get(id)

        if (!organisation || organisation.error) {
            organisationNotFound(id, organisation)
        }
        else {
            [organisation: organisation]
        }
    }

    def delete(String id) {
        organisationService.update(id, [status:'deleted'])

        redirect action: 'list'
    }

    def ajaxDelete(String id) {
        def result = organisationService.update(id, [status:'deleted'])

        respond result
    }

    def ajaxUpdate() {
        def organisationDetails = request.JSON

        def documents = organisationDetails.remove('documents')
        def links = organisationDetails.remove('links')
        def result = organisationService.update(organisationDetails.organisationId?:'', organisationDetails)

        def organisationId = organisationDetails.organisationId?:result.resp?.organisationId
        if (documents && !result.error) {
            documents.each { doc ->
                doc.organisationId = organisationId
                documentService.saveStagedImageDocument(doc)
            }
        }
        if (links && !result.error) {
            links.each { link ->
                link.organisationId = organisationId
                documentService.saveLink(link)
            }
        }

        if (result.error) {
            render result as JSON
        } else {
            render result.resp as JSON
        }
    }

    def getMembersForOrganisation(String id) {
        def adminUserId = userService.getCurrentUserId()

        if (id && adminUserId) {
            if (organisationService.isUserAdminForOrganisation(id) || organisationService.isUserGrantManagerForOrganisation(id)) {
                render organisationService.getMembersOfOrganisation(id) as JSON
            } else {
                render status:403, text: 'Permission denied'
            }
        } else if (adminUserId) {
            render status:400, text: 'Required params not provided: id'
        } else if (id) {
            render status:403, text: 'User not logged-in or does not have permission'
        } else {
            render status:500, text: 'Unexpected error'
        }
    }

    def addUserAsRoleToOrganisation() {
        String userId = params.userId
        String organisationId = params.entityId
        String role = params.role
        def adminUser = userService.getUser()

        if (adminUser && userId && organisationId && role) {
            if (role == 'caseManager' && !userService.userIsSiteAdmin()) {
                render status:403, text: 'Permission denied - ADMIN role required'
            } else if (organisationService.isUserAdminForOrganisation(organisationId)) {
                render organisationService.addUserAsRoleToOrganisation(userId, organisationId, role) as JSON
            } else {
                render status:403, text: 'Permission denied'
            }
        } else {
            render status:400, text: 'Required params not provided: userId, role, projectId'
        }
    }

    def removeUserWithRoleFromOrganisation() {
        String userId = params.userId
        String role = params.role
        String organisationId = params.entityId
        def adminUser = userService.getUser()

        if (adminUser && organisationId && role && userId) {
            if (organisationService.isUserAdminForOrganisation(organisationId)) {
                render organisationService.removeUserWithRoleFromOrganisation(userId, organisationId, role) as JSON
            } else {
                render status:403, text: 'Permission denied'
            }
        } else {
            render status:400, text: 'Required params not provided: userId, organisationId, role'
        }
    }

    /**
     * Redirects to the home page with an error message in flash scope.
     * @param response the response that triggered this method call.
     */
    private void organisationNotFound(id, response) {
        flash.message = "No organisation found with id: ${id}"
        if (response?.error) {
            flash.message += "<br/>${response.error}"
        }
        redirect(controller: 'home', model: [error: flash.message])
    }
}
