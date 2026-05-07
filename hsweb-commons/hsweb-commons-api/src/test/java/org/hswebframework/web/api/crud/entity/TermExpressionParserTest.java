package org.hswebframework.web.api.crud.entity;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TermExpressionParserTest {

    @Test
    public void testUrl(){
        List<Term> terms = TermExpressionParser.parse("type=email%20and%20provider=test");

        assertEquals(TermType.eq, terms.get(0).getTermType());
        assertEquals("type", terms.get(0).getColumn());
        assertEquals("email", terms.get(0).getValue());

        assertEquals(TermType.eq, terms.get(1).getTermType());
        assertEquals("provider", terms.get(1).getColumn());
        assertEquals("test", terms.get(1).getValue());

    }

    @Test
    public void testChinese() {
        {
            List<Term> terms = TermExpressionParser.parse("name = 我");

            assertEquals(TermType.eq, terms.get(0).getTermType());
            assertEquals("我", terms.get(0).getValue());

        }

        {
            List<Term> terms = TermExpressionParser.parse("name like %我%");

            assertEquals(TermType.like, terms.get(0).getTermType());
            assertEquals("%我%", terms.get(0).getValue());

        }
    }
    @Test
    public void testMap(){
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("name$like","我");

        map.put("$or$name","你");

        map.put("$nest","age = 10");


        List<Term> terms = TermExpressionParser.parse(map);

        assertEquals(3,terms.size());
        assertEquals("like",terms.get(0).getTermType());
        assertEquals("name",terms.get(0).getColumn());
        assertEquals("我",terms.get(0).getValue());

        assertEquals(Term.Type.or,terms.get(1).getType());
        assertEquals("name",terms.get(1).getColumn());
        assertEquals("你",terms.get(1).getValue());

        assertEquals(1,terms.get(2).getTerms().size());

        assertEquals("age",terms.get(2).getTerms().get(0).getColumn());

    }


    @Test
    public void test() {
        {
            List<Term> terms = TermExpressionParser.parse("name = 1");

            assertEquals(terms.get(0).getTermType(), TermType.eq);

        }

//        {
//            List<Term> terms = TermExpressionParser.parse("name = 1");
//
//            assertEquals(terms.get(0).getTermType(), TermType.not);
//
//        }
        {
            List<Term> terms = TermExpressionParser.parse("name > 1");

            assertEquals(terms.get(0).getTermType(), TermType.gt);
        }

        {
            List<Term> terms = TermExpressionParser.parse("name >= 1");

            assertEquals(terms.get(0).getTermType(), TermType.gte);
        }

        {
            List<Term> terms = TermExpressionParser.parse("name gte 1 and name not 1");

            assertEquals(terms.get(0).getTermType(), TermType.gte);
            assertEquals(terms.get(1).getTermType(), TermType.not);
        }

        {
            List<Term> terms = TermExpressionParser.parse("name gte 1 and (name not 1 or age gt 0)");

            assertEquals(terms.get(0).getTermType(), TermType.gte);
            assertEquals(terms.get(1).getTerms().get(0).getTermType(), TermType.not);
            assertEquals(terms.get(1).getTerms().get(1).getTermType(), TermType.gt);
        }
    }

    @Test
    public void testLessThan() {
        List<Term> terms = TermExpressionParser.parse("age < 18");

        assertEquals(1, terms.size());
        assertEquals(TermType.lt, terms.get(0).getTermType());
        assertEquals("age", terms.get(0).getColumn());
        assertEquals("18", terms.get(0).getValue());
    }

    @Test
    public void testLessThanOrEqual() {
        List<Term> terms = TermExpressionParser.parse("price <= 100");

        assertEquals(1, terms.size());
        assertEquals(TermType.lte, terms.get(0).getTermType());
        assertEquals("price", terms.get(0).getColumn());
        assertEquals("100", terms.get(0).getValue());
    }

    @Test
    public void testNotEqual() {
        List<Term> terms = TermExpressionParser.parse("status != active");

        assertEquals(1, terms.size());
        assertEquals(TermType.not, terms.get(0).getTermType());
        assertEquals("status", terms.get(0).getColumn());
        assertEquals("active", terms.get(0).getValue());
    }

    @Test
    public void testInOperator() {
        List<Term> terms = TermExpressionParser.parse("status in active,inactive,pending");

        assertEquals(1, terms.size());
        assertEquals(TermType.in, terms.get(0).getTermType());
        assertEquals("status", terms.get(0).getColumn());
    }

    @Test
    public void testNotInOperator() {
        List<Term> terms = TermExpressionParser.parse("type nin admin,root");

        assertEquals(1, terms.size());
        assertEquals(TermType.nin, terms.get(0).getTermType());
        assertEquals("type", terms.get(0).getColumn());
    }

    @Test
    public void testBetweenOperator() {
        List<Term> terms = TermExpressionParser.parse("age btw 18,60");

        assertEquals(1, terms.size());
        assertEquals(TermType.btw, terms.get(0).getTermType());
        assertEquals("age", terms.get(0).getColumn());
    }

    @Test
    public void testIsNull() {
        List<Term> terms = TermExpressionParser.parse("deletedTime isnull 1");

        assertEquals(1, terms.size());
        assertEquals(TermType.isnull, terms.get(0).getTermType());
        assertEquals("deletedTime", terms.get(0).getColumn());
    }

    @Test
    public void testNotNull() {
        List<Term> terms = TermExpressionParser.parse("createTime notnull 1");

        assertEquals(1, terms.size());
        assertEquals(TermType.notnull, terms.get(0).getTermType());
        assertEquals("createTime", terms.get(0).getColumn());
    }

    @Test
    public void testIsEmpty() {
        List<Term> terms = TermExpressionParser.parse("description empty 1");

        assertEquals(1, terms.size());
        assertEquals(TermType.empty, terms.get(0).getTermType());
        assertEquals("description", terms.get(0).getColumn());
    }

    @Test
    public void testNotEmpty() {
        List<Term> terms = TermExpressionParser.parse("name nempty 1");

        assertEquals(1, terms.size());
        assertEquals(TermType.nempty, terms.get(0).getTermType());
        assertEquals("name", terms.get(0).getColumn());
    }

    @Test
    public void testMultipleAndConditions() {
        List<Term> terms = TermExpressionParser.parse("name = test and age > 18 and status = active");

        assertEquals(3, terms.size());
        assertEquals(TermType.eq, terms.get(0).getTermType());
        assertEquals("name", terms.get(0).getColumn());
        assertEquals(TermType.gt, terms.get(1).getTermType());
        assertEquals("age", terms.get(1).getColumn());
        assertEquals(TermType.eq, terms.get(2).getTermType());
        assertEquals("status", terms.get(2).getColumn());
    }

    @Test
    public void testMultipleOrConditions() {
        List<Term> terms = TermExpressionParser.parse("status = active or status = pending or status = approved");

        assertEquals(3, terms.size());
        assertEquals(Term.Type.or, terms.get(1).getType());
        assertEquals(Term.Type.or, terms.get(2).getType());
    }

    @Test
    public void testNestedMultipleLevels() {
        List<Term> terms = TermExpressionParser.parse("age > 18 and (name = test or (status = active and type = user))");

        assertEquals(2, terms.size());
        assertEquals(TermType.gt, terms.get(0).getTermType());
        assertNotNull(terms.get(1).getTerms());
        assertEquals(2, terms.get(1).getTerms().size());
    }

    @Test
    public void testSpecialCharactersInValue() {
        List<Term> terms = TermExpressionParser.parse("email = user@example.com");

        assertEquals(1, terms.size());
        assertEquals("email", terms.get(0).getColumn());
        assertEquals("user@example.com", terms.get(0).getValue());
    }

    @Test
    public void testNumericValues() {
        {
            List<Term> terms = TermExpressionParser.parse("price = 99.99");
            assertEquals("99.99", terms.get(0).getValue());
        }
        {
            List<Term> terms = TermExpressionParser.parse("count = -10");
            assertEquals("-10", terms.get(0).getValue());
        }
    }

    @Test
    public void testEmptyStringValue() {
        List<Term> terms = TermExpressionParser.parse("description = \"\"");

        assertEquals(1, terms.size());
        assertEquals("description", terms.get(0).getColumn());
    }

    @Test
    public void testWhitespaceHandling() {
        List<Term> terms = TermExpressionParser.parse("  name   =   test   and   age   >   18  ");

        assertEquals(2, terms.size());
        assertEquals("name", terms.get(0).getColumn());
        assertEquals("test", terms.get(0).getValue());
        assertEquals("age", terms.get(1).getColumn());
        assertEquals("18", terms.get(1).getValue());
    }

    @Test
    public void testMapWithComplexNestedConditions() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("$nest", "(name = test or name = demo) and age > 18");

        List<Term> terms = TermExpressionParser.parse(map);

        assertEquals(1, terms.size());
        assertNotNull(terms.get(0).getTerms());
        assertTrue(terms.get(0).getTerms().size() > 0);
    }

    @Test
    public void testMapWithMultipleTermTypes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name$like", "%test%");
        map.put("age$gt", "18");
        map.put("status$in", "active,pending");
        map.put("deletedTime$isnull", "");

        List<Term> terms = TermExpressionParser.parse(map);

        assertEquals(4, terms.size());
        assertEquals("like", terms.get(0).getTermType());
        assertEquals("gt", terms.get(1).getTermType());
        assertEquals("in", terms.get(2).getTermType());
        assertEquals("isnull", terms.get(3).getTermType());
    }

    @Test
    public void testMixedAndOrWithoutParentheses() {
        List<Term> terms = TermExpressionParser.parse("name = test and age > 18 or status = active");

        assertNotNull(terms);
        assertTrue(terms.size() > 0);
    }

}