package com.readme.app.reading

object SampleContent {
    val sampleDocument: ReadingDocument = ReadingDocument(
        id = "readme_sample_doc",
        title = "ReadMe Sample",
        sections = listOf(
            ReadingSection(
                id = "readme_sample_section_intro",
                title = "Introduction",
                segments = listOf(
                    ReadingSegment(
                        id = "readme_sample_segment_1",
                        text = "Welcome to ReadMe."
                    ),
                    ReadingSegment(
                        id = "readme_sample_segment_2",
                        text = "This is a test of your selected voice, speech speed, pitch, and volume."
                    ),
                    ReadingSegment(
                        id = "readme_sample_segment_3",
                        text = "ReadMe is ready to read."
                    )
                )
            )
        )
    )
}
