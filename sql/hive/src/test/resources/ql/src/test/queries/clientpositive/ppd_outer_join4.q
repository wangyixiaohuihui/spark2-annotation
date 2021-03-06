set hive.optimize.ppd=true;
set hive.ppd.remove.duplicatefilters=false;

EXPLAIN
 FROM 
  src a
 LEFT OUTER JOIN
  src b
 ON (a.key = b.key)
 RIGHT OUTER JOIN 
  src c 
 ON (a.key = c.key)
 SELECT a.key, a.value, b.key, b.value, c.key
 WHERE a.key > '10' AND a.key < '20' AND b.key > '15' AND b.key < '25' AND sqrt(c.key) <> 13 ;

 FROM 
  src a
 LEFT OUTER JOIN
  src b
 ON (a.key = b.key)
 RIGHT OUTER JOIN 
  src c 
 ON (a.key = c.key)
 SELECT a.key, a.value, b.key, b.value, c.key
 WHERE a.key > '10' AND a.key < '20' AND b.key > '15' AND b.key < '25' AND sqrt(c.key) <> 13 ;

set hive.ppd.remove.duplicatefilters=true;

EXPLAIN
 FROM 
  src a
 LEFT OUTER JOIN
  src b
 ON (a.key = b.key)
 RIGHT OUTER JOIN 
  src c 
 ON (a.key = c.key)
 SELECT a.key, a.value, b.key, b.value, c.key
 WHERE a.key > '10' AND a.key < '20' AND b.key > '15' AND b.key < '25' AND sqrt(c.key) <> 13 ;

 FROM 
  src a
 LEFT OUTER JOIN
  src b
 ON (a.key = b.key)
 RIGHT OUTER JOIN 
  src c 
 ON (a.key = c.key)
 SELECT a.key, a.value, b.key, b.value, c.key
 WHERE a.key > '10' AND a.key < '20' AND b.key > '15' AND b.key < '25' AND sqrt(c.key) <> 13 ;
