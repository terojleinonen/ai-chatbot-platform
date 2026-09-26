import { useEffect, useState } from "react";
import Papa from "papaparse";
import { api } from "../services/api";

export default function ImportExportPage() {
  const [tenants, setTenants] = useState([]);
  const [tenantId, setTenantId] = useState("");
  const [count, setCount] = useState(0);

  useEffect(() => { api.tenantOptions().then(setTenants); }, []);
  useEffect(() => {
    if (tenantId) api.getFaqs(tenantId, { size: 1 }).then(p => setCount(p.total));
  }, [tenantId]);

  const exportCsv = async () => {
    const faqs = await api.exportFaqs(tenantId);
    const data = faqs.map(f => ({ question: f.question, answer: f.answer }));
    const csv = Papa.unparse(data);
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `tenant-${tenantId}-faqs.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleFile = (e) => {
    const file = e.target.files[0];
    if (!file || !tenantId) return;
    e.target.value = "";
    Papa.parse(file, {
      header: true,
      skipEmptyLines: true,
      complete: async (results) => {
        const rows = results.data
          .filter(r => r.question && r.answer)
          .map(r => ({ question: r.question, answer: r.answer }));
        if (!window.confirm(`Replace all FAQs of this tenant with ${rows.length} imported rows?`)) return;
        try {
          const saved = await api.importFaqs(Number(tenantId), rows);
          setCount(saved.length);
          alert(`Imported & trained ${saved.length} FAQs.`);
        } catch (err) {
          alert(err.message);
        }
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
            CSV format: <code>question,answer</code> headers. Importing replaces
            the tenant's existing FAQs. Currently {count} FAQs.
          </p>
        </>
      )}
    </div>
  );
}
