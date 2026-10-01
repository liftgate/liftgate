import { createServer } from "node:http";

createServer((request, response) => {
  const operator = request.headers.cookie?.includes("liftgate_session=operator");
  response.writeHead(request.url === "/api/v1/operator/summary" && !operator ? 404 : 200).end();
}).listen(3102);
