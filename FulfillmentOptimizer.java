import java.util.*;
import java.util.stream.Collectors;

public class FulfillmentOptimizer {

    // --- DATA MODELS ---
    public record FulfillmentCenter(String fc, String location, Map<String, Integer> inventory) {}
    public record ShippingOption(int cost, int deliveryDays) {}
    public record OrderItem(String sku, int quantity) {}
    public record Order(String orderId, String customer, String shipTo, List<OrderItem> items) {}

    public record ExecutionMetrics(
        String strategyName,
        double totalCost,
        int totalShipments,
        double avgDeliveryDays,
        int totalSplitPackages
    ) {}

    public static void main(String[] args) {
        // 1. Initial FC Inventory
        List<FulfillmentCenter> fcs = List.of(
            new FulfillmentCenter("FC-West", "NV", new HashMap<>(Map.of("A", 0, "B", 4, "C", 5))),
            new FulfillmentCenter("FC-Central", "TX", new HashMap<>(Map.of("A", 4, "B", 6, "C", 0))),
            new FulfillmentCenter("FC-East", "NJ", new HashMap<>(Map.of("A", 5, "B", 0, "C", 3)))
        );

        // 2. Shipping Rates Matrix (State -> FC -> Option)
        Map<String, Map<String, ShippingOption>> rates = Map.of(
            "CA", Map.of(
                "FC-West", new ShippingOption(5, 1),
                "FC-Central", new ShippingOption(8, 2),
                "FC-East", new ShippingOption(12, 4)
            ),
            "NY", Map.of(
                "FC-West", new ShippingOption(13, 4),
                "FC-Central", new ShippingOption(9, 3),
                "FC-East", new ShippingOption(5, 1)
            ),
            "TX", Map.of(
                "FC-West", new ShippingOption(8, 2),
                "FC-Central", new ShippingOption(4, 1),
                "FC-East", new ShippingOption(9, 3)
            )
        );

        // 3. Batch Orders Dataset
        List<Order> orders = List.of(
            new Order("O1", "Cust-1", "CA", List.of(new OrderItem("A", 2))),
            new Order("O2", "Cust-1", "CA", List.of(new OrderItem("B", 1))),
            new Order("O3", "Cust-2", "NY", List.of(new OrderItem("A", 1), new OrderItem("C", 2))),
            new Order("O4", "Cust-3", "TX", List.of(new OrderItem("B", 3), new OrderItem("C", 1))),
            new Order("O5", "Cust-2", "NY", List.of(new OrderItem("A", 3)))
        );

        int handlingFee = 3;

        // Run Baseline vs. Optmized Approach
        ExecutionMetrics defaultMetrics = runDefaultStrategy(cloneFCs(fcs), rates, orders, handlingFee);
        ExecutionMetrics optimizedMetrics = runOptimizedStrategy(cloneFCs(fcs), rates, orders, handlingFee);

        // Display Comparison Results
        printComparisonTable(defaultMetrics, optimizedMetrics);
    }

    
    private static ExecutionMetrics runOptimizedStrategy(
            List<FulfillmentCenter> fcs,
            Map<String, Map<String, ShippingOption>> rates,
            List<Order> orders,
            int handlingFee) {

        double totalCost = 0;
        int totalShipments = 0;
        int totalDays = 0;
        int splitPackages = 0;

        // Group orders by Customer and Ship-To State for Customer Consolidation
        Map<String, List<Order>> customerGroupedOrders = orders.stream()
                .collect(Collectors.groupingBy(o -> o.customer() + "_" + o.shipTo()));

        for (var entry : customerGroupedOrders.entrySet()) {
            String shipTo = entry.getValue().get(0).shipTo();

            // Aggregate total demand per customer
            Map<String, Integer> customerDemand = new HashMap<>();
            for (Order o : entry.getValue()) {
                for (OrderItem item : o.items()) {
                    customerDemand.merge(item.sku(), item.quantity(), Integer::sum);
                }
            }

            Map<String, Map<String, Integer>> customerShipments = new HashMap<>();

            while (customerDemand.values().stream().anyMatch(v -> v > 0)) {
                FulfillmentCenter bestFC = null;
                int maxCoverage = -1;
                int minCost = Integer.MAX_VALUE;
                int minDays = Integer.MAX_VALUE;

                
                for (FulfillmentCenter fc : fcs) {
                    ShippingOption opt = rates.get(shipTo).get(fc.fc());

                    // Calculate total items this FC can satisfy
                    int coverage = 0;
                    for (var demandEntry : customerDemand.entrySet()) {
                        if (demandEntry.getValue() > 0) {
                            coverage += Math.min(demandEntry.getValue(), fc.inventory().getOrDefault(demandEntry.getKey(), 0));
                        }
                    }

                    if (coverage == 0) continue;

                    
                    // Priority 1: Maximize Coverage (Minimizes Shipments / Handling Fees)
                    // Priority 2: Minimize Base Shipping Cost
                    // Priority 3: Minimize Delivery Days
                    boolean isBetter = false;

                    if (coverage > maxCoverage) {
                        isBetter = true;
                    } else if (coverage == maxCoverage) {
                        if (opt.cost() < minCost) {
                            isBetter = true;
                        } else if (opt.cost() == minCost) {
                            if (opt.deliveryDays() < minDays) {
                                isBetter = true;
                            }
                        }
                    }

                    if (isBetter) {
                        maxCoverage = coverage;
                        minCost = opt.cost();
                        minDays = opt.deliveryDays();
                        bestFC = fc;
                    }
                }

                if (bestFC == null) break; // Out of stock across all FCs

                // Fulfill demands from selected FC
                for (var demandEntry : customerDemand.entrySet()) {
                    String sku = demandEntry.getKey();
                    int needed = demandEntry.getValue();
                    if (needed > 0 && bestFC.inventory().getOrDefault(sku, 0) > 0) {
                        int alloc = Math.min(needed, bestFC.inventory().get(sku));
                        bestFC.inventory().put(sku, bestFC.inventory().get(sku) - alloc);
                        customerDemand.put(sku, needed - alloc);

                        customerShipments.computeIfAbsent(bestFC.fc(), k -> new HashMap<>()).merge(sku, alloc, Integer::sum);
                    }
                }
            }

            if (customerShipments.size() > 1) splitPackages += (customerShipments.size() - 1);

            for (String fcName : customerShipments.keySet()) {
                totalShipments++;
                ShippingOption opt = rates.get(shipTo).get(fcName);
                totalCost += opt.cost() + handlingFee;
                totalDays += opt.deliveryDays();
            }
        }

        return new ExecutionMetrics("Optmized Approach", totalCost, totalShipments,
                (double) totalDays / Math.max(1, totalShipments), splitPackages);
    }

    // --- Default STRATEGY ---
    private static ExecutionMetrics runDefaultStrategy(
            List<FulfillmentCenter> fcs,
            Map<String, Map<String, ShippingOption>> rates,
            List<Order> orders,
            int handlingFee) {

        double totalCost = 0;
        int totalShipments = 0;
        int totalDays = 0;
        int splitPackages = 0;

        for (Order order : orders) {
            Map<String, Map<String, Integer>> fcPackages = new HashMap<>();

            for (OrderItem item : order.items()) {
                String sku = item.sku();
                int qtyNeeded = item.quantity();

                List<FulfillmentCenter> sortedFCs = fcs.stream()
                    .filter(fc -> fc.inventory().getOrDefault(sku, 0) > 0)
                    .sorted(Comparator.comparingInt(a -> rates.get(order.shipTo()).get(a.fc()).cost()))
                    .toList();

                for (FulfillmentCenter fc : sortedFCs) {
                    if (qtyNeeded <= 0) break;
                    int available = fc.inventory().getOrDefault(sku, 0);
                    int alloc = Math.min(qtyNeeded, available);

                    fc.inventory().put(sku, available - alloc);
                    qtyNeeded -= alloc;

                    fcPackages.computeIfAbsent(fc.fc(), k -> new HashMap<>()).merge(sku, alloc, Integer::sum);
                }
            }

            if (fcPackages.size() > 1) splitPackages += (fcPackages.size() - 1);

            for (String fcName : fcPackages.keySet()) {
                totalShipments++;
                ShippingOption opt = rates.get(order.shipTo()).get(fcName);
                totalCost += opt.cost() + handlingFee;
                totalDays += opt.deliveryDays();
            }
        }

        return new ExecutionMetrics("Default Baseline", totalCost, totalShipments,
                (double) totalDays / Math.max(1, totalShipments), splitPackages);
    }

    private static List<FulfillmentCenter> cloneFCs(List<FulfillmentCenter> original) {
        return original.stream()
            .map(fc -> new FulfillmentCenter(fc.fc(), fc.location(), new HashMap<>(fc.inventory())))
            .collect(Collectors.toList());
    }

    private static void printComparisonTable(ExecutionMetrics basic, ExecutionMetrics optimized) {
        System.out.println("\n=========================== FULFILLMENT PLAN METRICS ===========================");
        System.out.printf("%-28s | %-18s | %-24s | %-12s%n", "Metric", "default Baseline", "Optmized Approach", "Delta");
        System.out.println("------------------------------------------------------------------------------------------------");
        System.out.printf("%-28s | $%-17.2f | $%-23.2f | %-12s%n", "Total Financial Cost", basic.totalCost(), optimized.totalCost(), 
                          String.format("%.1f%%", ((optimized.totalCost() - basic.totalCost()) / basic.totalCost()) * 100));
        System.out.printf("%-28s | %-18d | %-24d | %-12d%n", "Total Package Shipments", basic.totalShipments(), optimized.totalShipments(), optimized.totalShipments() - basic.totalShipments());
        System.out.printf("%-28s | %-18d | %-24d | %-12d%n", "Extra Split Packages", basic.totalSplitPackages(), optimized.totalSplitPackages(), optimized.totalSplitPackages() - basic.totalSplitPackages());
        System.out.printf("%-28s | %-18.2f | %-24.2f | %-12.2f%n", "Avg Delivery Time (Days)", basic.avgDeliveryDays(), optimized.avgDeliveryDays(), optimized.avgDeliveryDays() - basic.avgDeliveryDays());
        System.out.println("================================================================================================\n");
    }
}
