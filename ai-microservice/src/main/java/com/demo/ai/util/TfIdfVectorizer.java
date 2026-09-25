package com.demo.ai.util;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;

import java.util.*;
import java.util.stream.Collectors;

public class TfIdfVectorizer {
    private final List<String> vocabulary = new ArrayList<>();
    private final Map<String, Integer> wordToIndex = new HashMap<>();
    private double[] idf = new double[0];

    public void fit(List<String> documents) {
        Map<String, Integer> docFreq = new HashMap<>();
        for (String doc : documents) {
            for (String token : new HashSet<>(Arrays.asList(tokenize(doc)))) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        vocabulary.clear();
        vocabulary.addAll(docFreq.keySet());
        wordToIndex.clear();
        idf = new double[vocabulary.size()];
        int n = documents.size();
        for (int i = 0; i < vocabulary.size(); i++) {
            String word = vocabulary.get(i);
            wordToIndex.put(word, i);
            // Smoothed IDF (same formula as scikit-learn): ln((1 + n) / (1 + df)) + 1
            idf[i] = Math.log((1.0 + n) / (1.0 + docFreq.get(word))) + 1.0;
        }
    }

    public RealVector transform(String doc) {
        double[] tfidf = new double[vocabulary.size()];
        String[] tokens = tokenize(doc);
        Map<String, Long> counts = Arrays.stream(tokens)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Integer idx = wordToIndex.get(e.getKey());
            if (idx != null) {
                tfidf[idx] = e.getValue() * idf[idx];
            }
        }
        return new ArrayRealVector(tfidf);
    }

    public double cosineSimilarity(RealVector v1, RealVector v2) {
        double denom = v1.getNorm() * v2.getNorm();
        if (denom == 0) return 0.0;
        return v1.dotProduct(v2) / denom;
    }

    private String[] tokenize(String text) {
        if (text == null) return new String[0];
        String cleaned = text.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .trim();
        if (cleaned.isEmpty()) return new String[0];
        return cleaned.split("\\s+");
    }
}
