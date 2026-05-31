package com.example.productivityapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadmapConfidenceCalculatorTest {

    @Test
    fun high_whenDocumentContentProvided() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(documentContent = "Read chapters 1-3 and write a summary."),
        )
        assertEquals(RoadmapConfidence.HIGH, result)
    }

    @Test
    fun high_whenPhotoTextProvided() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(photoText = "Problem set 4: questions 1-10"),
        )
        assertEquals(RoadmapConfidence.HIGH, result)
    }

    @Test
    fun high_whenRequirementsProvided() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(requirements = "Include a bibliography and at least three sources."),
        )
        assertEquals(RoadmapConfidence.HIGH, result)
    }

    @Test
    fun medium_whenCourseSelectedWithoutInstructions() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(courseSelected = true),
        )
        assertEquals(RoadmapConfidence.MEDIUM, result)
    }

    @Test
    fun medium_whenEstimatedHoursProvidedWithoutInstructions() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(userEstimatedHours = 5.0),
        )
        assertEquals(RoadmapConfidence.MEDIUM, result)
    }

    @Test
    fun low_whenNoQualifyingInput() {
        val result = RoadmapConfidenceCalculator.calculate(RoadmapConfidenceInput())
        assertEquals(RoadmapConfidence.LOW, result)
    }

    @Test
    fun highOverridesMediumSignals() {
        val result = RoadmapConfidenceCalculator.calculate(
            RoadmapConfidenceInput(
                requirements = "Follow the rubric on page 2.",
                userEstimatedHours = 3.0,
                courseSelected = true,
            ),
        )
        assertEquals(RoadmapConfidence.HIGH, result)
    }
}
