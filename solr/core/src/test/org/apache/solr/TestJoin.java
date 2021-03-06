/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr;

import org.apache.solr.common.params.ModifiableSolrParams;
import org.noggit.JSONUtil;
import org.noggit.ObjectBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestJoin extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void beforeTests() throws Exception {
    System.setProperty("enable.update.log", "false"); // schema12 doesn't support _version_
    initCore("solrconfig.xml","schema12.xml");
  }


  @Test
  public void testJoin() throws Exception {
    assertU(add(doc("id", "1","name", "john", "title", "Director", "dept_s","Engineering")));
    assertU(add(doc("id", "2","name", "mark", "title", "VP", "dept_s","Marketing")));
    assertU(add(doc("id", "3","name", "nancy", "title", "MTS", "dept_s","Sales")));
    assertU(add(doc("id", "4","name", "dave", "title", "MTS", "dept_s","Support", "dept_s","Engineering")));
    assertU(add(doc("id", "5","name", "tina", "title", "VP", "dept_s","Engineering")));

    assertU(add(doc("id","10", "dept_id_s", "Engineering", "text","These guys develop stuff")));
    assertU(add(doc("id","11", "dept_id_s", "Marketing", "text","These guys make you look good")));
    assertU(add(doc("id","12", "dept_id_s", "Sales", "text","These guys sell stuff")));
    assertU(add(doc("id","13", "dept_id_s", "Support", "text","These guys help customers")));

    assertU(commit());

    ModifiableSolrParams p = params("sort","id asc");

    // test debugging
    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s}title:MTS", "fl","id", "debugQuery","true")
        ,"/debug/join/{!join from=dept_s to=dept_id_s}title:MTS=={'_MATCH_':'fromSetSize,toSetSize', 'fromSetSize':2, 'toSetSize':3}"
    );

    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s}title:MTS", "fl","id")
        ,"/response=={'numFound':3,'start':0,'docs':[{'id':'10'},{'id':'12'},{'id':'13'}]}"
    );

    // empty from
    assertJQ(req(p, "q","{!join from=noexist_s to=dept_id_s}*:*", "fl","id")
        ,"/response=={'numFound':0,'start':0,'docs':[]}"
    );

    // empty to
    assertJQ(req(p, "q","{!join from=dept_s to=noexist_s}*:*", "fl","id")
        ,"/response=={'numFound':0,'start':0,'docs':[]}"
    );

    // self join... return everyone with she same title as Dave
    assertJQ(req(p, "q","{!join from=title to=title}name:dave", "fl","id")
        ,"/response=={'numFound':2,'start':0,'docs':[{'id':'3'},{'id':'4'}]}"
    );

    // find people that develop stuff
    assertJQ(req(p, "q","{!join from=dept_id_s to=dept_s}text:develop", "fl","id")
        ,"/response=={'numFound':3,'start':0,'docs':[{'id':'1'},{'id':'4'},{'id':'5'}]}"
    );

    // self join on multivalued text field
    assertJQ(req(p, "q","{!join from=title to=title}name:dave", "fl","id")
        ,"/response=={'numFound':2,'start':0,'docs':[{'id':'3'},{'id':'4'}]}"
    );

    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s}title:MTS", "fl","id", "debugQuery","true")
        ,"/response=={'numFound':3,'start':0,'docs':[{'id':'10'},{'id':'12'},{'id':'13'}]}"
    );
    
    // expected outcome for a sub query matching dave joined against departments
    final String davesDepartments = 
      "/response=={'numFound':2,'start':0,'docs':[{'id':'10'},{'id':'13'}]}";

    // straight forward query
    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s}name:dave",
                 "fl","id"),
             davesDepartments);

    // variable deref for sub-query parsing
    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s v=$qq}",
                 "qq","{!dismax}dave",
                 "qf","name",
                 "fl","id", 
                 "debugQuery","true"),
             davesDepartments);

    // variable deref for sub-query parsing w/localparams
    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s v=$qq}",
                 "qq","{!dismax qf=name}dave",
                 "fl","id", 
                 "debugQuery","true"),
             davesDepartments);

    // defType local param to control sub-query parsing
    assertJQ(req(p, "q","{!join from=dept_s to=dept_id_s defType=dismax}dave",
                 "qf","name",
                 "fl","id", 
                 "debugQuery","true"),
             davesDepartments);

    // find people that develop stuff - but limit via filter query to a name of "john"
    // this tests filters being pushed down to queries (SOLR-3062)
    assertJQ(req(p, "q","{!join from=dept_id_s to=dept_s}text:develop", "fl","id", "fq", "name:john")
             ,"/response=={'numFound':1,'start':0,'docs':[{'id':'1'}]}"
            );

  }


  @Test
  public void testRandomJoin() throws Exception {
    int indexIter=50 * RANDOM_MULTIPLIER;
    int queryIter=50 * RANDOM_MULTIPLIER;

    // groups of fields that have any chance of matching... used to
    // increase test effectiveness by avoiding 0 resultsets much of the time.
    String[][] compat = new String[][] {
        {"small_s","small2_s","small2_ss","small3_ss"},
        {"small_i","small2_i","small2_is","small3_is"}
    };


    while (--indexIter >= 0) {
      int indexSize = random().nextInt(20 * RANDOM_MULTIPLIER);

      List<FldType> types = new ArrayList<>();
      types.add(new FldType("id",ONE_ONE, new SVal('A','Z',4,4)));
      types.add(new FldType("score_f",ONE_ONE, new FVal(1,100)));  // field used to score
      types.add(new FldType("small_s",ZERO_ONE, new SVal('a',(char)('c'+indexSize/3),1,1)));
      types.add(new FldType("small2_s",ZERO_ONE, new SVal('a',(char)('c'+indexSize/3),1,1)));
      types.add(new FldType("small2_ss",ZERO_TWO, new SVal('a',(char)('c'+indexSize/3),1,1)));
      types.add(new FldType("small3_ss",new IRange(0,25), new SVal('A','z',1,1)));
      types.add(new FldType("small_i",ZERO_ONE, new IRange(0,5+indexSize/3)));
      types.add(new FldType("small2_i",ZERO_ONE, new IRange(0,5+indexSize/3)));
      types.add(new FldType("small2_is",ZERO_TWO, new IRange(0,5+indexSize/3)));
      types.add(new FldType("small3_is",new IRange(0,25), new IRange(0,100)));

      clearIndex();
      Map<Comparable, Doc> model = indexDocs(types, null, indexSize);
      Map<String, Map<Comparable, Set<Comparable>>> pivots = new HashMap<>();

      for (int qiter=0; qiter<queryIter; qiter++) {
        String fromField;
        String toField;
        if (random().nextInt(100) < 5) {
          // pick random fields 5% of the time
          fromField = types.get(random().nextInt(types.size())).fname;
          // pick the same field 50% of the time we pick a random field (since other fields won't match anything)
          toField = (random().nextInt(100) < 50) ? fromField : types.get(random().nextInt(types.size())).fname;
        } else {
          // otherwise, pick compatible fields that have a chance of matching indexed tokens
          String[] group = compat[random().nextInt(compat.length)];
          fromField = group[random().nextInt(group.length)];
          toField = group[random().nextInt(group.length)];
        }

        Map<Comparable, Set<Comparable>> pivot = pivots.get(fromField+"/"+toField);
        if (pivot == null) {
          pivot = createJoinMap(model, fromField, toField);
          pivots.put(fromField+"/"+toField, pivot);
        }

        Collection<Doc> fromDocs = model.values();
        Set<Comparable> docs = join(fromDocs, pivot);
        List<Doc> docList = new ArrayList<>(docs.size());
        for (Comparable id : docs) docList.add(model.get(id));
        Collections.sort(docList, createComparator("_docid_",true,false,false,false));
        List sortedDocs = new ArrayList();
        for (Doc doc : docList) {
          if (sortedDocs.size() >= 10) break;
          sortedDocs.add(doc.toObject(h.getCore().getLatestSchema()));
        }

        Map<String,Object> resultSet = new LinkedHashMap<>();
        resultSet.put("numFound", docList.size());
        resultSet.put("start", 0);
        resultSet.put("docs", sortedDocs);

        // todo: use different join queries for better coverage

        SolrQueryRequest req = req("wt","json","indent","true", "echoParams","all",
            "q","{!join from="+fromField+" to="+toField
                + (random().nextInt(4)==0 ? " fromIndex=collection1" : "")
                +"}*:*"
        );

        String strResponse = h.query(req);

        Object realResponse = ObjectBuilder.fromJSON(strResponse);
        String err = JSONTestUtil.matchObj("/response", realResponse, resultSet);
        if (err != null) {
          log.error("JOIN MISMATCH: " + err
           + "\n\trequest="+req
           + "\n\tresult="+strResponse
           + "\n\texpected="+ JSONUtil.toJSON(resultSet)
           + "\n\tmodel="+ model
          );

          // re-execute the request... good for putting a breakpoint here for debugging
          String rsp = h.query(req);

          fail(err);
        }

      }
    }
  }


  Map<Comparable, Set<Comparable>> createJoinMap(Map<Comparable, Doc> model, String fromField, String toField) {
    Map<Comparable, Set<Comparable>> id_to_id = new HashMap<>();

    Map<Comparable, List<Comparable>> value_to_id = invertField(model, toField);

    for (Comparable fromId : model.keySet()) {
      Doc doc = model.get(fromId);
      List<Comparable> vals = doc.getValues(fromField);
      if (vals == null) continue;
      for (Comparable val : vals) {
        List<Comparable> toIds = value_to_id.get(val);
        if (toIds == null) continue;
        Set<Comparable> ids = id_to_id.get(fromId);
        if (ids == null) {
          ids = new HashSet<>();
          id_to_id.put(fromId, ids);
        }
        for (Comparable toId : toIds)
          ids.add(toId);
      }
    }

    return id_to_id;
  }


  Set<Comparable> join(Collection<Doc> input, Map<Comparable, Set<Comparable>> joinMap) {
    Set<Comparable> ids = new HashSet<>();
    for (Doc doc : input) {
      Collection<Comparable> output = joinMap.get(doc.id);
      if (output == null) continue;
      ids.addAll(output);
    }
    return ids;
  }

}
