package com.ohcp.cropagent.ontology;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
@Service
public class OntologyService {
 private static final String NS="https://ohcp.example/ontology#";
 private final Path root;
 public OntologyService(@Value("${agent.ontology.dir:../data/ontology}") String directory) { root=Path.of(directory); }
 public record QueryResult(String status, String scope, String sparql, List<Map<String,String>> rows) {}
 public QueryResult query(String type,String id) throws IOException {
  if(!Set.of("candidates","remedies").contains(type)) throw new IllegalArgumentException("未知查询类型");
  if(id==null || !id.matches("[A-Za-z][A-Za-z0-9]{0,79}")) throw new IllegalArgumentException("实体标识不合法");
  Model model=RDFDataMgr.loadModel(root.resolve("ohcp-ontology.ttl").toString());
  try {
   ParameterizedSparqlString query=new ParameterizedSparqlString(Files.readString(root.resolve("queries/"+type+".rq")));
   query.setIri(type.equals("candidates")?"symptom":"disorder",NS+id);
   List<Map<String,String>> rows=new ArrayList<>();
   try(QueryExecution execution=QueryExecutionFactory.create(query.asQuery(),model)) {
    ResultSet result=execution.execSelect();
    while(result.hasNext()) {
     QuerySolution solution=result.next(); Map<String,String> row=new LinkedHashMap<>();
     result.getResultVars().forEach(key->{if(solution.contains(key)) row.put(key,solution.get(key).toString());});
     rows.add(row);
    }
   }
   return new QueryResult(rows.isEmpty()?"NOT_COVERED":"FOUND","仅通用本体知识，作物适用范围尚待审核，不作为确诊或施肥依据",query.toString(),rows);
  } finally {model.close();}
 }
}
