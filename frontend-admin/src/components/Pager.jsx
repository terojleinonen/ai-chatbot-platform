// Previous / next controls for a PageResponse ({page, totalPages, total}); pages are zero-based.
export default function Pager({ data, onPage, label = "items" }) {
  if (!data || data.total === 0) return null;
  const { page, totalPages, total } = data;
  return (
    <div className="flex items-center gap-3 mt-3 text-sm" aria-label="Pagination">
      <button
        className="px-2 py-1 rounded border disabled:opacity-40"
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
      >
        Previous
      </button>
      <span>Page {page + 1} of {Math.max(totalPages, 1)} · {total} {label}</span>
      <button
        className="px-2 py-1 rounded border disabled:opacity-40"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        Next
      </button>
    </div>
  );
}
