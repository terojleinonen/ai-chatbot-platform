import { useEffect, useState } from "react";
import { api } from "../services/api";

export default function FaqPage() {
  const [tenants, setTenants] = useState([]);
  const [tenantId, setTenantId] = useState("");
  const [faqs, setFaqs] = useState([]);
  const [q, setQ] = useState("");
  const [a, setA] = useState("");
  const [editing, setEditing] = useState(null);
  const [editQ, setEditQ] = useState("");
  const [editA, setEditA] = useState("");
  const [status, setStatus] = useState("");

  useEffect(() => { api.listTenants().then(setTenants); }, []);

  const loadFaqs = () => {
    if (tenantId) api.getFaqs(tenantId).then(setFaqs);
  };

  useEffect(() => { loadFaqs(); }, [tenantId]);

  const addFaq = async (e) => {
    e.preventDefault();
    if (!tenantId) return;
    await api.addFaq(Number(tenantId), q, a);
    setQ(""); setA("");
    loadFaqs();
  };

  const startEdit = (faq) => {
    setEditing(faq);
    setEditQ(faq.question);
    setEditA(faq.answer);
  };

  const saveEdit = async () => {
    await api.updateFaq(editing.id, { question: editQ, answer: editA });
    setEditing(null);
    loadFaqs();
  };

  const retrain = async () => {
    setStatus("Training...");
    try {
      setStatus(await api.trainAi(tenantId));
    } catch (err) {
      setStatus(err.message);
    }
  };

  const deleteFaq = async (id) => {
    if (!window.confirm("Delete this FAQ?")) return;
    await api.deleteFaq(id);
    loadFaqs();
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">FAQs</h1>
      <div className="mb-4 flex gap-2 items-center">
        <span className="font-semibold">Tenant:</span>
        <select
          className="border p-2 rounded"
          value={tenantId}
          onChange={(e) => setTenantId(e.target.value)}
        >
          <option value="">-- select --</option>
          {tenants.map(t => (
            <option key={t.id} value={t.id}>{t.name}</option>
          ))}
        </select>
        {tenantId && (
          <button className="bg-green-600 text-white px-3 py-2 rounded" onClick={retrain}>
            Retrain AI
          </button>
        )}
        {status && <span className="text-sm text-gray-600">{status}</span>}
      </div>
      {tenantId && (
        <>
          <form onSubmit={addFaq} className="bg-white p-4 rounded shadow mb-4">
            <h2 className="font-semibold mb-2">Add FAQ</h2>
            <input
              className="border p-2 w-full mb-2 rounded"
              placeholder="Question"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <textarea
              className="border p-2 w-full mb-2 rounded"
              placeholder="Answer"
              value={a}
              onChange={(e) => setA(e.target.value)}
            />
            <button className="bg-blue-600 text-white px-4 py-2 rounded">
              Add
            </button>
          </form>
          <h2 className="font-semibold mb-2">Existing FAQs</h2>
          <div className="space-y-2">
            {faqs.map(f => (
              <div key={f.id} className="bg-white p-3 rounded shadow">
                <div className="flex justify-between items-start">
                  <div>
                    <div className="font-semibold">{f.question}</div>
                    <div className="text-gray-600 text-sm">{f.answer}</div>
                  </div>
                  <div className="space-x-2">
                    <button className="text-sm text-blue-600" onClick={() => startEdit(f)}>
                      Edit
                    </button>
                    <button className="text-sm text-red-600" onClick={() => deleteFaq(f.id)}>
                      Delete
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
          {editing && (
            <div className="fixed inset-0 bg-black/40 flex items-center justify-center">
              <div className="bg-white p-4 rounded shadow w-full max-w-md">
                <h3 className="font-semibold mb-2">Edit FAQ</h3>
                <input
                  className="border p-2 w-full mb-2 rounded"
                  value={editQ}
                  onChange={(e) => setEditQ(e.target.value)}
                />
                <textarea
                  className="border p-2 w-full mb-2 rounded"
                  value={editA}
                  onChange={(e) => setEditA(e.target.value)}
                />
                <div className="flex justify-end gap-2">
                  <button className="px-3 py-1 rounded border" onClick={() => setEditing(null)}>
                    Cancel
                  </button>
                  <button
                    className="px-3 py-1 rounded bg-blue-600 text-white"
                    onClick={saveEdit}
                  >
                    Save
                  </button>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
