import { useEffect, useState } from "react";
import Papa from "papaparse";
import { api } from "../services/api";

export default function ImportExportPage() {
  const [tenants, setTenants] = useState([]);
  const [tenantId, setTenantId] = useState("");
  const [faqs, setFaqs] = useState([]);

  useEffect(() => { api.listTenants().then(setTenants); }, []);
  useEffect(() => { if (tenantId) api.getFaqs(tenantId).then(setFaqs); }, [tenantId]);

  const exportCsv = () => {
    const data = faqs.map(f => ({ question: f.question, answer: f.answer }));
    const csv = Papa.unparse(data);
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `tenant-${tenantId}-faqs.csv`;
    a.click();
  };

  const handleFile = (e) => {
    const file = e.target.files[0];
    if (!file || !tenantId) return;
    Papa.parse(file, {
      header: true,
      complete: async (results) => {
        const rows = results.data.filter(r => r.question && r.answer);
        await api.trainAi(tenantId, rows);
        alert(`Imported & trained ${rows.length} FAQs.`);
      }
    });
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Import / Export FAQs</h1>
      <div className="mb-3 flex gap-2 items-center">
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
      </div>
      {tenantId && (
        <>
          <div className="mb-4 space-x-2">
            <button
              className="bg-blue-600 text-white px-4 py-2 rounded"
              onClick={exportCsv}
            >
              Export CSV
            </button>
            <label className="inline-block bg-gray-200 px-4 py-2 rounded cursor-pointer">
              Import CSV
              <input
                type="file"
                accept=".csv"
                className="hidden"
                onChange={handleFile}
              />
            </label>
          </div>
          <p className="text-sm text-gray-600">
            CSV format: <code>question,answer</code> headers.
          </p>
        </>
      )}
    </div>
  );
}
