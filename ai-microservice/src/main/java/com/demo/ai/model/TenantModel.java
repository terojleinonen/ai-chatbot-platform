package com.demo.ai.model;

import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.util.TfIdfVectorizer;
import org.apache.commons.math3.linear.RealVector;

import java.util.ArrayList;
import java.util.List;

public class TenantModel {
    public static final String NO_DATA = "No FAQ data available for this tenant yet.";
    public static final String NO_MATCH = "I'm not sure yet. Could you rephrase your question?";

    private final List<TenantFaqEntity> faqs;
    private final TfIdfVectorizer vectorizer;
    private final List<RealVector> faqVectors;

    public TenantModel(List<TenantFaqEntity> faqs) {
        this.faqs = faqs;
        this.vectorizer = new TfIdfVectorizer();
        List<String> questions = faqs.stream().map(TenantFaqEntity::getQuestion).toList();
        vectorizer.fit(questions);
        faqVectors = new ArrayList<>();
        for (String q : questions) {
            faqVectors.add(vectorizer.transform(q));
        }
    }

    public String getBestAnswer(String message) {
        if (faqs.isEmpty()) {
            return NO_DATA;
        }
        RealVector userVec = vectorizer.transform(message);
        double bestScore = 0;
        TenantFaqEntity best = null;
        for (int i = 0; i < faqs.size(); i++) {
            double score = vectorizer.cosineSimilarity(userVec, faqVectors.get(i));
            if (score > bestScore) {
                bestScore = score;
                best = faqs.get(i);
            }
        }
        if (best != null && bestScore > 0.2) {
            return best.getAnswer();
        }
        return NO_MATCH;
    }
}
