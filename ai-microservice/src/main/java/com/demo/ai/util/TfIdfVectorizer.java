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

    // Common English function words; matching on these makes unrelated questions look similar.
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "for", "with",
            "by", "from", "about", "as", "into", "is", "are", "was", "were", "be", "been", "am",
            "do", "does", "did", "have", "has", "had", "can", "could", "will", "would", "should",
            "i", "me", "my", "we", "our", "you", "your", "it", "its", "they", "them", "their",
            "this", "that", "these", "those", "there", "what", "when", "where", "which", "who",
            "how", "why", "please", "hi", "hello", "any", "some", "so", "not", "no"
    );

    private String[] tokenize(String text) {
        if (text == null) return new String[0];
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+"))
                .filter(t -> !t.isEmpty() && !STOP_WORDS.contains(t))
                .map(TfIdfVectorizer::stem)
                .toArray(String[]::new);
    }

    /** Very light suffix stripping so "opening"/"open" and "hours"/"hour" match. */
    static String stem(String word) {
        if (word.length() > 5 && word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.length() > 4 && word.endsWith("ed")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) return word.substring(0, word.length() - 1);
        return word;
    }
}
