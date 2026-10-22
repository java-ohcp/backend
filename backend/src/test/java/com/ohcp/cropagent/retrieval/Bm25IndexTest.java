package com.ohcp.cropagent.retrieval;

import com.ohcp.cropagent.knowledge.KnowledgeRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Bm25IndexTest {
    @Test void rebuildReplacesStaleDocumentsAndDocumentFrequency() {
        var kb = mock(KnowledgeRepository.class);
        when(kb.elements()).thenReturn(List.of("N"));
        when(kb.evidenceText("N")).thenReturn("黄化黄叶");
        var index = new Bm25Index(kb); index.build();
        assertFalse(index.search("黄化", 8).isEmpty());
        when(kb.elements()).thenReturn(List.of("Ca"));
        when(kb.evidenceText("Ca")).thenReturn("脐腐裂果");
        index.build(); index.build();
        assertEquals(1, index.size());
        assertTrue(index.search("黄化", 8).isEmpty());
        assertEquals("Ca", index.search("脐腐", 8).get(0).element());
    }
}
