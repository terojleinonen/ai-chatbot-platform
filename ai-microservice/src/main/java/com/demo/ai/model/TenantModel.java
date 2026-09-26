package com.demo.ai.model;

import com.demo.ai.entity.TenantFaqEntity;
import com.demo.ai.util.TfIdfVectorizer;
import org.apache.commons.math3.linear.RealVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A tenant's FAQs indexed with TF-IDF. Used to pick the FAQs most relevant to a question (the context sent to
 * the LLM when a tenant has more FAQs than fit in a prompt), and as the keyword-matching fallback when no LLM
 * is configured or the LLM call fails.
 */
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

    public List<TenantFaqEntity> faqs() {
        return faqs;
    }

    /** All FAQs, most similar to the message first; FAQs with equal similarity keep their original order. */
    public List<TenantFaqEntity> rank(String message) {
        RealVector userVec = vectorizer.transform(message);
        double[] scores = new double[faqs.size()];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < faqs.size(); i++) {
            scores[i] = vectorizer.cosineSimilarity(userVec, faqVectors.get(i));
            order.add(i);
        }
        order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        return order.stream().map(faqs::get).toList();
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
