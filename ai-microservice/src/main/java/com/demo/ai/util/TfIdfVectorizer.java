package com.demo.ai.util;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;

import java.util.*;
import java.util.stream.Collectors;

public class TfIdfVectorizer {
    private final List<String> vocabulary = new ArrayList<>();
    private final Map<String, Integer> wordToIndex = new HashMap<>();

    public void fit(List<String> documents) {
        Set<String> vocabSet = new HashSet<>();
        for (String doc : documents) {
            String[] tokens = tokenize(doc);
            vocabSet.addAll(Arrays.asList(tokens));
        }
        vocabulary.clear();
        vocabulary.addAll(vocabSet);
        wordToIndex.clear();
        for (int i = 0; i < vocabulary.size(); i++) {
            wordToIndex.put(vocabulary.get(i), i);
        }
    }

    public RealVector transform(String doc) {
        double[] tf = new double[vocabulary.size()];
        String[] tokens = tokenize(doc);
        Map<String, Long> counts = Arrays.stream(tokens)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Integer idx = wordToIndex.get(e.getKey());
            if (idx != null) {
                tf[idx] = e.getValue();
            }
        }
        return new ArrayRealVector(tf);
    }

    public double cosineSimilarity(RealVector v1, RealVector v2) {
        double denom = v1.getNorm() * v2.getNorm();
        if (denom == 0) return 0.0;
        return v1.dotProduct(v2) / denom;
    }

    private String[] tokenize(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .trim()
                .split("\s+");
    }
}
