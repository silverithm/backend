package com.silverithm.vehicleplacementsystem.service;

import com.silverithm.vehicleplacementsystem.dto.CoupleRequestDTO;
import com.silverithm.vehicleplacementsystem.dto.ElderlyDTO;
import com.silverithm.vehicleplacementsystem.dto.EmployeeDTO;
import com.silverithm.vehicleplacementsystem.dto.FixedAssignmentsDTO;
import com.silverithm.vehicleplacementsystem.entity.Chromosome;
import com.silverithm.vehicleplacementsystem.entity.DispatchType;
import com.silverithm.vehicleplacementsystem.entity.DistanceScore;
import com.silverithm.vehicleplacementsystem.entity.DurationScore;
import com.silverithm.vehicleplacementsystem.entity.FixedAssignments;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
public class GeneticAlgorithm {

    private final ThreadPoolTaskExecutor executor;

    private static final int MAX_ITERATIONS = 300;
    private static final int POPULATION_SIZE = 20000;
    private static final double MUTATION_RATE = 0.9;
    private static final double CROSSOVER_RATE = 0.7;
    private static final int BATCH_SIZE = 200;


    private final List<EmployeeDTO> employees;
    private final List<ElderlyDTO> elderlys;
    private final List<CoupleRequestDTO> couples;
    private final FixedAssignments fixedAssignments;
    private final Map<String, Map<String, Integer>> distanceMatrix;
    private final DispatchType dispatchType;
    private final String userName;

    private final SSEService sseService;

    public GeneticAlgorithm(ThreadPoolTaskExecutor executor, List<EmployeeDTO> employees, List<ElderlyDTO> elderly,
                            List<CoupleRequestDTO> couples,
                            Map<String, Map<String, Integer>> distanceMatrix,
                            List<FixedAssignmentsDTO> fixedAssignments, DispatchType dispatchType, String userName,
                            SSEService sseService
    ) {
        this.executor = executor;
        this.employees = employees;
        this.elderlys = elderly;
        this.couples = couples;
        this.distanceMatrix = distanceMatrix;
        this.fixedAssignments = generateFixedAssignmentMap(fixedAssignments, elderlys, employees);
        this.dispatchType = dispatchType;
        this.sseService = sseService;
        this.userName = userName;
    }

    public List<Chromosome> run() throws Exception {

        // 초기 솔루션 생성
        List<Chromosome> chromosomes;
        try {

            chromosomes = generateInitialPopulation(fixedAssignments);
            sseService.notify(userName, 20);

            for (int i = 0; i < MAX_ITERATIONS; i++) {

                sseService.notify(userName, String.format("%.1f", 20 + ((i / (double) MAX_ITERATIONS) * 60)));

                // 평가
                evaluatePopulation(chromosomes);
                // 선택
                List<Chromosome> selectedChromosomes = selectChromosomes(chromosomes);
                // 교차
                List<Chromosome> offspringChromosomes = crossover(selectedChromosomes);
                // 돌연변이
                List<Chromosome> mutatedChromosomes = mutate(offspringChromosomes);

                // 다음 세대 생성
                chromosomes = combinePopulations(selectedChromosomes, offspringChromosomes, mutatedChromosomes);
//                log.info(chromosomes.get(0).getFitness() + " " + chromosomes.get(0).getGenes());

            }


        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("genetic algorithm run exception : " + e);
        }
        // 반복

        Collections.sort(chromosomes, (c1, c2) -> Double.compare(c2.getFitness(), c1.getFitness()));
        // 최적의 솔루션 추출
        return chromosomes;

    }

    private List<Chromosome> selectChromosomes(List<Chromosome> chromosomes) {
        return chromosomes.subList(0, POPULATION_SIZE);
    }

    private FixedAssignments generateFixedAssignmentMap(List<FixedAssignmentsDTO> fixedAssignmentDtos,
                                                        List<ElderlyDTO> elderlys,
                                                        List<EmployeeDTO> employees) {
        FixedAssignments fixedAssignments = new FixedAssignments(fixedAssignmentDtos, employees, elderlys);
        return fixedAssignments;
    }

    private List<Chromosome> generateInitialPopulation(FixedAssignments fixedAssignments) throws Exception {

        List<Chromosome> chromosomes = new ArrayList<>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            chromosomes.add(new Chromosome(couples, employees, elderlys, fixedAssignments.getFixedAssignments()));
        }
        return chromosomes;
    }

    //    private void evaluatePopulation(List<Chromosome> chromosomes) {
//        for (Chromosome chromosome : chromosomes) {
//            chromosome.setFitness(calculateFitness(chromosome));
//        }
//    }
// 2. 병렬 처리 방식
    private void evaluatePopulation(List<Chromosome> chromosomes) {
        // 작업을 여러 개의 배치로 나눔
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < chromosomes.size(); i += BATCH_SIZE) {
            // 200개씩 묶어서 처리
            int end = Math.min(i + BATCH_SIZE, chromosomes.size());
            List<Chromosome> batch = chromosomes.subList(i, end);

            // 각 배치를 별도 스레드에서 실행
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (Chromosome chromosome : batch) {
                    chromosome.setFitness(calculateFitness(chromosome));
                }
            }, executor);

            futures.add(future);
        }

        // 모든 배치의 처리가 완료될 때까지 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private double calculateFitness(Chromosome chromosome) {

        double fitness = 0.0;

        if (!isValidChromosome(chromosome)) {
            return 0.0;
        }

        fitness += calculateFitnessForDepartureTimes(chromosome);
        fitness += addFitnessForProximity(chromosome);

        return fitness;
    }

    private boolean isValidChromosome(Chromosome chromosome) {
        // 모든 제약조건 검사
        return evaluateFrontSeatAssignments(chromosome) &&
                evaluateFixedAssignments(chromosome) &&
                evaluateCoupleAssignments(chromosome);
    }

    private boolean evaluateCoupleAssignments(Chromosome chromosome) {
        // Elderly ID를 인덱스로 매핑하는 맵 생성
        Map<Long, Integer> elderlyIdToIndex = new HashMap<>();
        for (int i = 0; i < elderlys.size(); i++) {
            elderlyIdToIndex.put(elderlys.get(i).id(), i);
        }

        // 각 부부 어르신에 대해 평가
        for (CoupleRequestDTO couple : couples) {
            long elderly1Id = couple.elderId1();
            long elderly2Id = couple.elderId2();

            // 실제 ID를 인덱스로 변환
            Integer elderly1Index = elderlyIdToIndex.get(elderly1Id);
            Integer elderly2Index = elderlyIdToIndex.get(elderly2Id);

            if (elderly1Index == null || elderly2Index == null) {
                // 인덱스 변환 실패시 다음 부부로 건너뛰기
                continue;
            }

            boolean found = false;

            // 부부가 같은 차량에 배정되었는지 확인
            for (List<Integer> gene : chromosome.getGenes()) {
                if (gene.contains(elderly1Index) && gene.contains(elderly2Index)) {
                    found = true;
                    break;
                }
            }

            // 부부가 다른 차량에 배정된 경우
            if (!found) {
                return false;
            }
        }
        return true; // 모든 부부가 같은 차량에 배정됨
    }

    private double calculateFitnessForDepartureTimes(Chromosome chromosome) {
        double fitness;
        List<Double> departureTimes = calculateDepartureTimes(chromosome);
        chromosome.setDepartureTimes(departureTimes);
        double totalDepartureTime = departureTimes.stream().mapToDouble(Double::doubleValue).sum();

        if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
            fitness = 10000000 / ((totalDepartureTime + 1.0));
            return fitness;
        }

        fitness = 10000000 / ((totalDepartureTime + 1.0) / 1000);
//        log.info("total departure time : " + totalDepartureTime + ", fitness : " + fitness);
        return fitness;
    }

    private double addFitnessForProximity(Chromosome chromosome) {

        double fitness = 0.0;

        if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
            for (int i = 0; i < chromosome.getGenes().size(); i++) {
                for (int j = 0; j < chromosome.getGenes().get(i).size() - 1; j++) {
                    int elderlyIndex1 = chromosome.getGenes().get(i).get(j);
                    int elderlyIndex2 = chromosome.getGenes().get(i).get(j + 1);
                    fitness += calculateFitnessForFromAndTo("Elderly_" + elderlys.get(elderlyIndex1).id(),
                            "Elderly_" + elderlys.get(elderlyIndex2).id());
                }
                fitness = addFitnessForDispatchTypes(chromosome, fitness, i);
            }
        }

        if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
            for (int i = 0; i < chromosome.getGenes().size(); i++) {
                for (int j = 0; j < chromosome.getGenes().get(i).size() - 1; j++) {
                    int elderlyIndex1 = chromosome.getGenes().get(i).get(j);
                    int elderlyIndex2 = chromosome.getGenes().get(i).get(j + 1);

                    if (calculateFitnessForFromAndTo("Elderly_" + elderlys.get(elderlyIndex1).id(),
                            "Elderly_" + elderlys.get(elderlyIndex2).id()) == 10000) {
                        fitness += 10000;
                    } else {

                        fitness += calculateFitnessForFromAndTo("Employee_" + employees.get(i).id(),
                                "Elderly_" + elderlys.get(elderlyIndex1).id());
                    }

                }
                fitness = addFitnessForDispatchTypes(chromosome, fitness, i);
            }
        }

        return fitness;
    }

    private boolean evaluateFixedAssignments(Chromosome chromosome) {
        return fixedAssignments.evaluateFitness(chromosome);
    }

    private boolean evaluateFrontSeatAssignments(Chromosome chromosome) {
        for (int i = 0; i < employees.size(); i++) {
            boolean frontSeatAssigned = false;
            for (int j = 0; j < chromosome.getGenes().get(i).size(); j++) {
                if (elderlys.get(j).requiredFrontSeat()) {
                    if (frontSeatAssigned) {
                        return false;
                    }
                    frontSeatAssigned = true;
                }
            }
        }
        return true;
    }

    private double addFitnessForDispatchTypes(Chromosome chromosome, double fitness, int i) {
        if (dispatchType.equals(DispatchType.DISTANCE_OUT) || dispatchType.equals(DispatchType.DURATION_OUT)) {
            if (employees.get(i).isDriver()) {
                fitness += calculateFitnessForFromAndTo("Elderly_" + elderlys.get(
                        chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)).id(), "Company");
            }

            if (!employees.get(i).isDriver()) {
                fitness += calculateFitnessForFromAndTo("Elderly_" + elderlys.get(
                                chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)).id(),
                        "Employee_" + employees.get(i).id());
            }
        }

        if (dispatchType.equals(DispatchType.DURATION_IN) || dispatchType.equals(DispatchType.DISTANCE_IN)) {
            if (employees.get(i).isDriver()) {
                fitness += calculateFitnessForFromAndTo("Company",
                        "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
            }
            if (!employees.get(i).isDriver()) {
                fitness += calculateFitnessForFromAndTo("Employee_" + employees.get(i).id(),
                        "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
            }
            fitness += calculateFitnessForFromAndTo(
                    "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1))
                            .id(), "Company");
        }
        return fitness;
    }

    private double calculateFitnessForFromAndTo(String from, String to) {

        double score = 0;

        if (dispatchType == DispatchType.DURATION_OUT || dispatchType == DispatchType.DURATION_IN) {
            score = DurationScore.getScore(distanceMatrix.get(from).get(to));
        }

        if (dispatchType == DispatchType.DISTANCE_OUT || dispatchType == DispatchType.DISTANCE_IN) {
            score = DistanceScore.getScore(distanceMatrix.get(from).get(to));
        }

        return score;
    }


    private List<Double> calculateDepartureTimes(Chromosome chromosome) {

        List<Double> departureTimes = new ArrayList<>();

        if (dispatchType.equals(DispatchType.DISTANCE_OUT) || dispatchType.equals(DispatchType.DURATION_OUT)) {
            for (int i = 0; i < chromosome.getGenes().size(); i++) {
                double departureTime = 0.0;
                for (int j = 0; j < chromosome.getGenes().get(i).size() - 1; j++) {
                    String company = "Company";
//                    log.info("calculateDepartureTimes : " + chromosome.getGenes().get(i).get(j) + " "
//                            + chromosome.getGenes().get(i).get(j + 1));

                    String startNodeId = "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(j)).id();
                    String destinationNodeId = "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(j + 1)).id();

                    if (j == 0) {
                        departureTime += distanceMatrix.get(company)
                                .get("Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
                    }

                    departureTime += distanceMatrix.get(startNodeId).get(destinationNodeId);
                }

                departureTime += distanceMatrix.get("Elderly_" + elderlys.get(
                                chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)))
                        .get("Employee_" + employees.get(i).id());

                if (employees.get(i).isDriver()) {
                    departureTime += distanceMatrix.get("Elderly_" + elderlys.get(
                                    chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)).id())
                            .get("Company");
                }

                if (!employees.get(i).isDriver()) {
                    departureTime += distanceMatrix.get("Elderly_" + elderlys.get(
                                    chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)).id())
                            .get("Employee_" + employees.get(i).id());
                }

                departureTimes.add(departureTime);
            }
        }

        if (dispatchType.equals(DispatchType.DURATION_IN) || dispatchType.equals(DispatchType.DISTANCE_IN)) {
            for (int i = 0; i < chromosome.getGenes().size(); i++) {
                String company = "Company";
                double departureTime = 0.0;

                for (int j = 0; j < chromosome.getGenes().get(i).size() - 1; j++) {
//                    log.info("calculateDepartureTimes : " + chromosome.getGenes().get(i).get(j) + " "
//                            + chromosome.getGenes().get(i).get(j + 1));
//                    log.info(chromosome.getGenes().toString());

                    String startNodeId = "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(j)).id();
                    String destinationNodeId = "Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(j + 1)).id();
                    if (j == 0) {
                        departureTime += distanceMatrix.get("Employee_" + employees.get(i).id())
                                .get("Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
                    }

                    departureTime += distanceMatrix.get(startNodeId).get(destinationNodeId);
                }

                if (employees.get(i).isDriver()) {
                    departureTime += distanceMatrix.get("Company")
                            .get("Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
                }

                if (!employees.get(i).isDriver()) {
                    departureTime += distanceMatrix.get("Employee_" + employees.get(i).id())
                            .get("Elderly_" + elderlys.get(chromosome.getGenes().get(i).get(0)).id());
                }

                departureTime += distanceMatrix.get("Elderly_" + elderlys.get(
                        chromosome.getGenes().get(i).get(chromosome.getGenes().get(i).size() - 1)).id()).get(company);

                departureTimes.add(departureTime);
            }
        }

        return departureTimes;
    }


    private List<Chromosome> crossover(List<Chromosome> selectedChromosomes) {
        Random rand = new Random();
        List<Chromosome> offspring = new ArrayList<>();

        for (int i = 0; i < selectedChromosomes.size(); i += 2) {
            Chromosome parent1 = Chromosome.copy(selectedChromosomes.get(i));
            Chromosome parent2 = Chromosome.copy(selectedChromosomes.get(i + 1));
            // Crossover 확률에 따라 진행
            if (rand.nextDouble() < CROSSOVER_RATE) {
                offspring.addAll(multiPointCrossover(parent1, parent2));
                continue;
            }

            if (rand.nextDouble() >= CROSSOVER_RATE) {
                offspring.add(parent1);
                offspring.add(parent2);
                continue;
            }

        }

        return offspring;
    }


    private List<Chromosome> multiPointCrossover(Chromosome parent1, Chromosome parent2) {
        int[] crossoverPoints = createSortedRandomCrossoverPoints(parent1);

        Chromosome child1 = Chromosome.copy(parent1);
        Chromosome child2 = Chromosome.copy(parent2);

        swapGeneticSegments(parent1, parent2, crossoverPoints, child1, child2);

        fixDuplicateAssignments(child1, elderlys);
        fixDuplicateAssignments(child2, elderlys);

        return Arrays.asList(child1, child2);
    }

    private int[] createSortedRandomCrossoverPoints(Chromosome parent1) {
        Random rand = new Random();
        int[] crossoverPoints = new int[2];
        for (int i = 0; i < crossoverPoints.length; i++) {
            crossoverPoints[i] = rand.nextInt(parent1.getGenes().size());
        }
        Arrays.sort(crossoverPoints);
        return crossoverPoints;
    }

    private void swapGeneticSegments(Chromosome parent1, Chromosome parent2, int[] crossoverPoints, Chromosome child1,
                                     Chromosome child2) {
        for (int i = 0; i < crossoverPoints.length; i++) {
            int start = i == 0 ? 0 : crossoverPoints[i - 1];
            int end = crossoverPoints[i];
            for (int j = start; j < end; j++) {
                List<Integer> parent1Gene = parent1.getGenes().get(j);
                List<Integer> parent2Gene = parent2.getGenes().get(j);
                int minLength = Math.min(parent1Gene.size(), parent2Gene.size());
                for (int k = 0; k < minLength; k++) {
                    if (i % 2 == 0) {
                        child1.getGenes().get(j).set(k, parent1Gene.get(k));
                        child2.getGenes().get(j).set(k, parent2Gene.get(k));
                        continue;
                    }
                    if (i % 2 != 0) {
                        child1.getGenes().get(j).set(k, parent2Gene.get(k));
                        child2.getGenes().get(j).set(k, parent1Gene.get(k));
                        continue;
                    }
                }
            }
        }

    }

    private void fixDuplicateAssignments(Chromosome child, List<ElderlyDTO> elderlys) {
        Set<Integer> assignedElderly = new HashSet<>();
        for (List<Integer> gene : child.getGenes()) {
            for (int i = 0; i < gene.size(); i++) {
                int elderlyId = gene.get(i);
                if (!assignedElderly.add(elderlyId)) {
                    // 중복 발생 시, 다른 노인으로 교체
                    for (int newElderlyId = 0; newElderlyId < elderlys.size(); newElderlyId++) {
                        if (!assignedElderly.contains(newElderlyId)) {
                            gene.set(i, newElderlyId);
                            assignedElderly.add(newElderlyId);
                            break;
                        }
                    }
                }
            }
        }
    }

    private List<Chromosome> mutate(List<Chromosome> offspringChromosomes) throws Exception {
        Random rand = new Random();
        List<Chromosome> mutatedChromosomes = new ArrayList<>();

        for (Chromosome chromosome : offspringChromosomes) {
            // 염색체 깊은 복사
            Chromosome newChromosome = Chromosome.copy(chromosome);

            if (rand.nextDouble() < MUTATION_RATE) {
                int mutationPoint1 = rand.nextInt(newChromosome.getGenes().size());
                List<Integer> employeeAssignment = newChromosome.getGenes().get(mutationPoint1);
                int mutationPoint2 = rand.nextInt(employeeAssignment.size());

                int mutationPoint3 = rand.nextInt(newChromosome.getGenes().size());
                List<Integer> employeeAssignment2 = newChromosome.getGenes().get(mutationPoint3);
                int mutationPoint4 = rand.nextInt(employeeAssignment2.size());

                // 염색
                int tempElderly = employeeAssignment2.get(mutationPoint4);
                employeeAssignment2.set(mutationPoint4, employeeAssignment.get(mutationPoint2));
                employeeAssignment.set(mutationPoint2, tempElderly);
            }

            mutatedChromosomes.add(newChromosome); // 변이된 염색체를 리스트에 추가
        }

        return mutatedChromosomes; // 변이된 새로운 염색체 리스트 반환
    }

    private List<Chromosome> combinePopulations(List<Chromosome> chromosomes, List<Chromosome> offspringChromosomes,
                                                List<Chromosome> mutatedChromosomes)
            throws Exception {
        List<Chromosome> combinedChromosomes = combineChromosome(chromosomes, offspringChromosomes, mutatedChromosomes);

        Collections.sort(combinedChromosomes, (c1, c2) -> Double.compare(c2.getFitness(), c1.getFitness()));

        return combinedChromosomes;
    }


    private List<Chromosome> combineChromosome(List<Chromosome> chromosomes, List<Chromosome> offspringChromosomes,
                                               List<Chromosome> mutatedChromosomes) {
        List<Chromosome> combinedChromosomes = new ArrayList<>();
        combinedChromosomes.addAll(chromosomes);
        combinedChromosomes.addAll(offspringChromosomes);
        combinedChromosomes.addAll(mutatedChromosomes);
        return combinedChromosomes;
    }


}
