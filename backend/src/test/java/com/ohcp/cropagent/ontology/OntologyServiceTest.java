package com.ohcp.cropagent.ontology;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OntologyServiceTest {
 private final OntologyService service=new OntologyService("../data/ontology");
 @Test void executesBothExternalQueries() throws Exception {
  assertEquals(1,service.query("candidates","OldLeafUniformChlorosis").rows().size());
  assertEquals(1,service.query("remedies","NitrogenDeficiency").rows().size());
  assertTrue(service.query("candidates","OldLeafUniformChlorosis").sparql().contains("OldLeafUniformChlorosis"));
 }
 @Test void refusesUnknownCoverage() throws Exception {assertEquals("NOT_COVERED",service.query("candidates","Unknown").status());}
 @Test void rejectsInjectionAndUnknownQuery(){
  assertThrows(IllegalArgumentException.class,()->service.query("candidates","x> UNION {"));
  assertThrows(IllegalArgumentException.class,()->service.query("arbitrary","Rice"));
 }
}
