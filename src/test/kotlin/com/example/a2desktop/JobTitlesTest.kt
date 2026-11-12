package com.example.a2desktop

import com.example.a2desktop.logic.EmailKind
import com.example.a2desktop.logic.JobEvent
import com.example.a2desktop.logic.employerOf
import com.example.a2desktop.logic.jobNameOf
import com.example.a2desktop.logic.jobTitleKey
import com.example.a2desktop.logic.jobTitleOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The name a job found in email is given: the job title and nothing else — shapes taken from the first real scan, with invented employers. */
class JobTitlesTest {
    private fun title(subject: String) = jobTitleOf(subject)

    @Test fun aQuotedTitleIsTakenOutOfTheSentenceAroundIt() {
        assertEquals("IT Support Engineer", title("Your job application for 'IT Support Engineer'"))
        assertEquals("IT Support Engineer", title("Your job application for ‘IT Support Engineer’"), "curly quotes too")
    }

    @Test fun aJobsReferenceCodeIsNotPartOfItsName() {
        assertEquals("Patient Administrator", title("Application for job 289-CS-355-A Patient Administrator"))
        assertEquals("Ward Clerk - AMU", title("Application for vacancy 200-NN-7662939-A&C-B - Ward Clerk - AMU"))
        assertEquals("Ward Clerk - AMU", title("Application for job 200-NN-7662939-A&C-B Ward Clerk - AMU"), "the same job whichever way the hospital's system words it")
        assertEquals("Band 3 Patient Pathway Coordinator", title("Application for job 396-7833310-DE-GV-Z Band 3 Patient Pathway Coordinator"))
        assertEquals("Clinic Clerk - Maternity at Northgate", title("[290-WCCS-2440] Clinic Clerk - Maternity at Northgate"), "a reference in brackets at the front")
    }

    @Test fun theWordsAroundATitleAreDroppedWhateverWayRoundTheyCome() {
        assertEquals("Sales Assistant (M25) - Brixton", title("Your application for Sales Assistant (M25) - Brixton"))
        assertEquals("Junior Data Engineer(Apprentice)", title("Application Outcome Junior Data Engineer(Apprentice)"))
        assertEquals("Trainee Field Service Engineer", title("Acme: Your application to our role of Trainee Field Service Engineer"))
        assertEquals("ICT Technician - Schools", title("You've successfully applied for the ICT Technician - Schools vacancy"))
        assertEquals("IT Help desk support analyst", title("IT Help desk support analyst application at Arora Group Head Office"))
        assertEquals("IT Technician", title("IT Technician – Unfortunately, you have not been successful this time"))
        assertEquals("Riverside Grand Hotel London Airport", title("First stage interview - Riverside Grand Hotel London Airport."))
    }

    @Test fun anInvitationsTrailingBoilerplateIsCutOffAndItsVacancyNumberToo() {
        val expected = "Universal Credit Review - Executive Officer - National"
        assertEquals(expected, title("Vacancy 749 749 - Universal Credit Review - Executive Officer - National - Invite to Pre Recorded Video Interview"))
        assertEquals(expected, title("Vacancy 749 749 - Universal Credit Review - Executive Officer - National - Please don’t forget to attend your Pre-Recorded Video Interview"))
    }

    @Test fun aSubjectThatIsAlreadyJustTheTitleIsLeftAlone() {
        assertEquals("Deskside Support Technician - ABM UK", title("Deskside Support Technician - ABM UK"))
        assertEquals("IT Technician - Xavier Catholic Education Trust", title("IT Technician - Xavier Catholic Education Trust"))
        assertEquals("Application Support Analyst - Acme", title("Re: Application Support Analyst - Acme"), "a title that merely starts with 'Application' is a title")
    }

    @Test fun aSubjectThatNamesNoJobGivesNoTitle() {
        listOf(
            "Application Update", "Thank You For Applying!", "Thank you for your application", "We've received your application",
            "We've sent your application to Cerco", "update on your application from hireful", "Your Application at UK Mission Enterprise",
            "Thanks for applying to ABM UK", "Re: Thank you for your application",
        ).forEach { assertNull(title(it), it) }
    }

    @Test fun whenNoJobIsNamedTheEmployerIs() {
        assertEquals("Cerco", employerOf("We've sent your application to Cerco", "Jobboard <no-reply@jobs.jobboard.co.uk>"))
        assertEquals("ABM UK", employerOf("Thanks for applying to ABM UK", "Workable <x@workable.example>"))
        assertEquals("hireful", employerOf("update on your application from hireful", "Hireful <x@hireful.example>"))
        assertEquals("UK Mission Enterprise", employerOf("Your Application at UK Mission Enterprise", "Workday <x@workday.example>"))
        assertEquals("swipejobs", employerOf("Thank You For Your Application", "swipejobs <jobs@swipejobs.example>"), "no employer in the subject: the sender's name")
        assertNull(employerOf("First stage interview", "\"Walker, Jamie\" <jamie.walker@hotel.example>"), "a person writing to you is not an employer")
        assertNull(employerOf("Hello", "noreply@somewhere.example"))
    }

    private fun ev(subject: String, from: String = "Acme <jobs@acme.example>", at: Long = 1) =
        JobEvent("m$at$subject", "t", EmailKind.CONFIRMATION, at, from, subject, "evidence", emptyList())

    @Test fun aJobFoundInEmailIsNamedByItsTitleNeverTheSenderOrTheBoilerplate() {
        assertEquals("Ward Clerk - AMU", jobNameOf(listOf(ev("Application for job 200-NN-7662939-A&C-B Ward Clerk - AMU", "Trac <noreply@trac.example>"), ev("Application for vacancy 200-NN-7662939-A&C-B - Ward Clerk - AMU", "Trac <noreply@recruit.trac.example>", at = 2))))
        assertEquals("IT Support Engineer", jobNameOf(listOf(ev("Your job application for 'IT Support Engineer'", "reed.co.uk <no-reply@jobs.reed.example>"))), "no 'Reed.co.uk —' in front and nothing about 'your job application'")
        assertEquals("Cerco", jobNameOf(listOf(ev("We've sent your application to Cerco", "reed.co.uk <no-reply@jobs.reed.example>"))), "no title anywhere: the employer")
        assertEquals("swipejobs", jobNameOf(listOf(ev("Thank You For Your Application", "swipejobs <jobs@swipejobs.example>"))), "no employer either: the sender")
        assertEquals("Application", jobNameOf(listOf(ev("Thank you for your application", "<>"))), "nothing to go on at all: a plain 'Application', never boilerplate or blank")
    }

    @Test fun anEmailThatNamesNothingIsLabelledWithWhoSentItSoItCanBeRecognisedAndDeleted() {
        assertEquals("Application from ukme@workday.example", jobNameOf(listOf(ev("Thank You For Applying!", "ukme@workday.example"))))
        assertEquals("Application from jobs@acme.example", jobNameOf(listOf(ev("Thank you for your application", "<jobs@acme.example>"))))
    }

    @Test fun anEmployerNamedInASubjectIsNotTheSameAsTheSendersName() {
        assertEquals("hireful", com.example.a2desktop.logic.employerNamedIn("update on your application from hireful"))
        assertNull(com.example.a2desktop.logic.employerNamedIn("Thank You For Your Application"), "the subject names no employer")
        assertEquals("swipejobs", employerOf("Thank You For Your Application", "swipejobs <jobs@swipejobs.example>"), "but the sender's name is the fallback for naming")
        assertEquals("hireful", com.example.a2desktop.logic.employerKeyOf("Hireful"))
        assertEquals(com.example.a2desktop.logic.employerKeyOf("UK Mission Enterprise"), com.example.a2desktop.logic.employerKeyOf("uk-mission  enterprise"))
    }

    @Test fun whenEmailsDisagreeTheTitleMostOfThemSayWinsAndTheMoreSpecificOneBreaksATie() {
        assertEquals("Ward Clerk - AMU", jobNameOf(listOf(ev("Application for job 1-A-2 Ward Clerk - AMU", at = 1), ev("Application for job 1-A-2 Ward Clerk - AMU", at = 2), ev("Update: Ward Clerk", at = 3))))
        assertEquals("Ward Clerk - AMU", jobNameOf(listOf(ev("Application for job 1-A-2 Ward Clerk", at = 1), ev("Application for job 1-A-2 Ward Clerk - AMU", at = 2))))
    }

    @Test fun titlesAreComparedByTheirWordsNotTheirPunctuationOrOrder() {
        assertEquals(jobTitleKey("Ward Clerk - AMU"), jobTitleKey("ward clerk, AMU"))
        assertEquals(jobTitleKey("Clerk Ward AMU"), jobTitleKey("Ward Clerk - AMU"))
    }
}
